#!/usr/bin/env python3
"""Focused Paper 26.2 UX/force verification with three real protocol clients and a restart.

Uses a new isolated directory, explicit EULA consent and checksum-pinned server inputs.
This inspects real inventory components and clicks; it is not a visual Minecraft-client review.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import time
import zipfile

ROOT = Path(__file__).resolve().parents[1]
PAPER_SHA = "3cce0a4922baf79bd8e1275ee5ff9f073f24675770e09b0d4be9442d513e7a6b"

def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--accept-eula', action='store_true')
    parser.add_argument('--ux-polish', action='store_true', help='One focused localized voting/GUI polish session')
    parser.add_argument('--directory', type=Path, required=True)
    parser.add_argument('--paper-jar', type=Path, required=True)
    parser.add_argument('--mojang-cache', type=Path, required=True)
    parser.add_argument('--storage-type', choices=['JSON', 'SQLITE'], default='JSON')
    args = parser.parse_args()
    directory = args.directory.resolve()
    if not args.accept_eula or directory == ROOT or ROOT in directory.parents or directory.exists() and any(directory.iterdir()):
        parser.error('Explicit EULA consent and a new directory outside the checkout are required')
    assert sha(args.paper_jar) == PAPER_SHA
    directory.mkdir(parents=True, exist_ok=True)
    plugins = directory / 'plugins'
    plugins.mkdir()
    artifacts = ROOT / 'reputation-paper/build/libs'
    hashes = {}
    for name in ['DexturReputation.jar', 'DexturReputationProbe.jar']:
        hashes[name] = sha(artifacts / name)
        shutil.copy2(artifacts / name, plugins / name)
    shutil.copy2(args.paper_jar, directory / 'paper.jar')
    with zipfile.ZipFile(args.paper_jar) as archive:
        expected, _, filename = archive.read('META-INF/download-context').decode().strip().split('\t')
    assert sha(args.mojang_cache) == expected
    (directory / 'cache').mkdir()
    shutil.copy2(args.mojang_cache, directory / 'cache' / filename)
    (directory / 'eula.txt').write_text('eula=true\n')
    (directory / 'server.properties').write_text('server-ip=127.0.0.1\nserver-port=25566\nonline-mode=false\nview-distance=2\nsimulation-distance=2\nmax-players=3\nlevel-name=ux-world\ngenerate-structures=false\n')
    config = plugins / 'DexturReputation'
    config.mkdir()
    with zipfile.ZipFile(artifacts / 'DexturReputation.jar') as archive:
        (config / 'storage.yml').write_text(archive.read('storage.yml').decode().replace('type: JSON', 'type: '+args.storage_type))
        # QA-only short waits and raised operation capacity. Initial eligibility thresholds stay unchanged.
        voting = archive.read('voting.yml').decode().replace('cooldown-seconds: 7200','cooldown-seconds: 1').replace('base-daily-limit: 3','base-daily-limit: 100')
        (config / 'voting.yml').write_text(voting)
        if not args.ux_polish:
            (config / 'anti-abuse.yml').write_text(archive.read('anti-abuse.yml').decode().replace('suspicious-count: 10','suspicious-count: 2'))
    java = str(Path(os.environ['JAVA_HOME']) / 'bin/java')
    proof = {'storage': args.storage_type, 'artifacts': hashes, 'runs': [], 'visual_client_review': 'NOT PERFORMED',
             'qa_configuration': 'Initial thresholds unchanged; cooldowns 1s, base daily limit 100; burst threshold '+('unchanged' if args.ux_polish else '2')}
    for run in ([1] if args.ux_polish else [1, 2]):
        log = directory / ('server-'+str(run)+'.log')
        clients, handles = [], []
        with log.open('w') as output:
            server = subprocess.Popen([java, '-Xms256m', '-Xmx1200m', '-Dio.netty.eventLoopThreads=2', '-Ddextur.probe.ux-polish=true' if args.ux_polish else '-Ddextur.probe.admin-ux=true', '-Ddextur.probe.ux-run='+str(run), '-jar', 'paper.jar', '--nogui'], cwd=directory, stdin=subprocess.PIPE, stdout=output, stderr=subprocess.STDOUT)
            try:
                deadline = time.monotonic()+120
                while time.monotonic()<deadline:
                    if server.poll() is not None:
                        raise RuntimeError('Paper stopped during startup; see '+str(log))
                    if 'Done (' in log.read_text(errors='replace'):
                        break
                    time.sleep(.25)
                else:
                    raise RuntimeError('Paper startup timeout')
                for name in ['UxAdmin','UxVoter','Fen0nim']:
                    handle = (directory / (name+'-'+str(run)+'.log')).open('w');handles.append(handle)
                    clients.append(subprocess.Popen([sys.executable, str(ROOT/'scripts/paper-test-client.py'), '--username', name, '--capture-messages'], stdout=handle, stderr=subprocess.STDOUT))
                server.wait(timeout=240)
                text = log.read_text()
                assert server.returncode == 0 and ('PROBE_POLISH_PASS:' if args.ux_polish else 'PROBE_UX_PASS:') in text and 'PROBE_PASS:' in text and 'PROBE_FAIL:' not in text, log
                proof['runs'].append({'run': run, 'status': 'PASS', 'log': str(log)})
            finally:
                if server.poll() is None:
                    server.stdin.write(b'stop\n');server.stdin.flush()
                    try:server.wait(timeout=30)
                    except subprocess.TimeoutExpired:server.kill();server.wait(timeout=10)
                for client in clients:
                    if client.poll() is None:client.terminate()
                for client in clients:
                    try:client.wait(timeout=10)
                    except subprocess.TimeoutExpired:client.kill();client.wait(timeout=10)
                for handle in handles:handle.close()
        print('UX_PAPER_PASS', args.storage_type, 'run', run, flush=True)
    actor = (directory/'UxVoter-1.log').read_text();target = (directory/'Fen0nim-1.log').read_text()
    for text in [actor,target]:
        assert ('POLISH_WIRE_MARKER' if args.ux_polish else 'UX_WIRE_MARKER') in text
        assert 'повыс' in text and 'пониз' in text, 'Both sides must receive real chat packets'
        assert 'reputation.restore.pending' not in text
        if args.ux_polish:
            assert 'Помощь игрокам' in text and 'Грубость' in text, 'Localized reasons missing in wire messages'
        else:
            assert 'аннулирована' in text and 'восстановлена' in text, 'Both vote author and recipient need moderation feedback'
    if not args.ux_polish:
        assert 'заблокировал' in actor and 'восстановлен' in actor, 'Restriction target feedback missing'
    proof['notification_wire_verification'] = 'PASS'
    proof['status'] = 'PASS'
    (directory/('polish-proof.json' if args.ux_polish else 'ux-proof.json')).write_text(json.dumps(proof, indent=2, ensure_ascii=False)+'\n')
    print('UX_VERIFIED', args.storage_type, '1 launch, localized votes/history, GUI styling/refresh and both-sided chat packets' if args.ux_polish else '2 launches, GUI routes, force restart and both-sided chat packets', flush=True)

if __name__ == '__main__':
    main()

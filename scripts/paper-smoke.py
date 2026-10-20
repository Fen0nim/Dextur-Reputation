#!/usr/bin/env python3
"""Real Paper 26.2 smoke/restart test. Requires Java 25, Docker and explicit EULA acceptance."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import secrets
import shutil
import subprocess
import time
import urllib.parse
import urllib.request
import zipfile

PAPER_HASH = "3cce0a4922baf79bd8e1275ee5ff9f073f24675770e09b0d4be9442d513e7a6b"
PAPER_URL = f"https://fill-data.papermc.io/v1/objects/{PAPER_HASH}/paper-26.2-134.jar"
ROOT = Path(__file__).resolve().parents[1]


def command(*args, **kwargs):
    return subprocess.check_output(args, text=True, **kwargs).strip()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--accept-eula", action="store_true", help="Accept https://aka.ms/MinecraftEULA for this isolated test")
    parser.add_argument("--directory", type=Path, required=True, help="New/empty test directory outside the checkout")
    parser.add_argument("--artifacts-directory", type=Path, default=ROOT / "reputation-paper/build/libs")
    parser.add_argument("--paper-jar", type=Path)
    parser.add_argument("--placeholderapi-jar", type=Path, help="Optional trusted PlaceholderAPI 2.12.3 jar")
    parser.add_argument("--administration",action="store_true",help="Phase 7: real moderation, investigation, bulk confirmations, GUI and restart")
    parser.add_argument("--phase6-json-fixture",type=Path,help="Complete genuine Phase 6 snapshot and original configuration")
    parser.add_argument("--policies",action="store_true",help="Phase 6: native administration, policy, locks, health, migration and restart")
    parser.add_argument("--phase5-json-fixture",type=Path,help="Genuine complete Phase 5 format-5 snapshot with original configuration beside it")
    parser.add_argument("--recovery",action="store_true",help="Phase 5: enrollment, native active time, login days, rewards, GUI, providers and restart")
    parser.add_argument("--recovery-disabled",action="store_true",help="Verify explicitly disabled recovery")
    parser.add_argument("--phase4-json-fixture",type=Path,help="Real full Phase 4 format-4 snapshot; used with --recovery")
    parser.add_argument("--tiers", action="store_true", help="Phase 4: real tiers, hysteresis, display ownership, GUI, API and all PAPI values")
    parser.add_argument("--luckperms-jar", type=Path, help="Trusted full LuckPerms Bukkit distribution; used with --tiers")
    parser.add_argument("--storage-type", choices=("JSON", "SQLITE", "MYSQL"), default="JSON")
    parser.add_argument("--database-engine",choices=("MARIADB","MYSQL"),default="MARIADB",help="Real SQL server engine for storage.type MYSQL")
    parser.add_argument("--legacy-mysql", action="store_true", help="Test unchanged Phase 1 storage.yml")
    parser.add_argument("--mojang-cache", type=Path, help="Reuse a cached Mojang server jar after checking Paper's embedded SHA-256")
    parser.add_argument("--voting", action="store_true", help="Exercise Phase 2 with two local protocol 776 clients and relaxed QA eligibility/cooldowns")
    parser.add_argument("--protection", action="store_true", help="Exercise Phase 3 fractional voting, pending review, private Trust CAS and restart with three real local clients")
    parser.add_argument("--phase3-json-fixture", type=Path, help="Preserved real Phase 3 format-3 snapshot, upgraded with --tiers")
    parser.add_argument("--phase2-json-fixture", type=Path, help="Import a preserved real Phase 2 format-2 snapshot through offline StorageAdmin before Paper startup")
    args = parser.parse_args()
    if sum((args.voting,args.protection,args.tiers,args.recovery,args.policies,args.administration))>1:
        parser.error("Run --voting, --protection, --tiers --recovery and --policies separately")
    if args.phase5_json_fixture and not args.policies:parser.error("--phase5-json-fixture requires --policies")
    if (args.phase4_json_fixture or args.recovery_disabled) and not args.recovery:parser.error("Phase 5 options require --recovery")
    if args.phase3_json_fixture and not args.tiers:
        parser.error("--phase3-json-fixture requires --tiers")
    if args.phase2_json_fixture and not args.protection:
        parser.error("--phase2-json-fixture requires --protection")
    if args.legacy_mysql and args.storage_type != "MYSQL":
        parser.error("--legacy-mysql requires --storage-type MYSQL")
    if not args.accept_eula:
        parser.error("Explicit --accept-eula is required; the script never accepts legal terms implicitly")
    directory = args.directory.resolve()
    if directory == ROOT or ROOT in directory.parents:
        parser.error("--directory must be outside the repository")
    if directory.exists() and any(directory.iterdir()):
        parser.error("--directory must be new or empty; existing server files are never overwritten")
    directory.mkdir(parents=True, exist_ok=True)
    artifacts = args.artifacts_directory.resolve()
    for name in ("DexturReputation.jar", "DexturReputationProbe.jar") + (("DexturReputation-StorageAdmin.jar",) if args.phase2_json_fixture or args.tiers or args.recovery or args.policies or args.administration else ()):
        if not (artifacts / name).is_file():
            parser.error(f"Build {name} first: ./gradlew build :reputation-paper:runtimeProbeJar")
    paper = directory / "paper.jar"
    if args.paper_jar:
        shutil.copyfile(args.paper_jar, paper)
    else:
        urllib.request.urlretrieve(PAPER_URL, paper)
    if hashlib.sha256(paper.read_bytes()).hexdigest() != PAPER_HASH:
        raise RuntimeError("Paper checksum mismatch")
    if args.mojang_cache:
        with zipfile.ZipFile(paper) as archive:
            expected, _, filename = archive.read("META-INF/download-context").decode().strip().split("\t")
        if hashlib.sha256(args.mojang_cache.read_bytes()).hexdigest() != expected:
            raise RuntimeError("Mojang cache checksum mismatch")
        (directory / "cache").mkdir()
        shutil.copyfile(args.mojang_cache, directory / "cache" / filename)
    (directory / "eula.txt").write_text("eula=true\n")
    (directory / "server.properties").write_text("server-ip=127.0.0.1\nserver-port=25566\nonline-mode=false\nview-distance=2\nsimulation-distance=2\nmax-players=3\nlevel-name=smoke-world\ngenerate-structures=false\n")
    plugins = directory / "plugins"
    config = plugins / "DexturReputation"
    config.mkdir(parents=True)
    for name in ("DexturReputation.jar", "DexturReputationProbe.jar"):
        shutil.copyfile(artifacts / name, plugins / name)
    resources = ROOT / "reputation-paper/src/main/resources"
    for name in ("config.yml", "integrations.yml"):
        shutil.copyfile(resources / name, config / name)
    if args.voting or args.protection or args.tiers or args.recovery or args.policies or args.administration:
        voting = (resources / "voting.yml").read_text().replace("minimum-playtime-seconds: 108000", "minimum-playtime-seconds: 0").replace("minimum-account-age-seconds: 604800", "minimum-account-age-seconds: 0").replace("cooldown-seconds: 7200", "cooldown-seconds: 0").replace("base-daily-limit: 3", "base-daily-limit: 1000")
        (config / "voting.yml").write_text(voting)
        protection = (resources / "anti-abuse.yml").read_text().replace("enabled: true", "enabled: false")
        if args.protection:
            protection = protection.replace("voting-weight:\n  enabled: false", "voting-weight:\n  enabled: true").replace("base: {like: 1.0, dislike: 1.0}", "base: {like: 0.35, dislike: 0.35}").replace("burst-protection:\n  enabled: false", "burst-protection:\n  enabled: true").replace("suspicious-count: 10", "suspicious-count: 2")
        # QA-only policy: legacy workflow keeps unit weights; Phase 3 isolates fractional and pending behavior.
        (config / "anti-abuse.yml").write_text(protection)
    container = "dextur-smoke-" + secrets.token_hex(6)
    password = secrets.token_urlsafe(32)
    environment = os.environ.copy()
    environment.update(MARIADB_ROOT_PASSWORD=password, MARIADB_DATABASE="reputation", MARIADB_USER="reputation", MARIADB_PASSWORD=password,MYSQL_ROOT_PASSWORD=password,MYSQL_DATABASE="reputation",MYSQL_USER="reputation",MYSQL_PASSWORD=password)
    created = False
    try:
        port = "3306"
        if args.storage_type == "MYSQL":
            prefix="MARIADB" if args.database_engine=="MARIADB" else "MYSQL"
            image="mariadb:11.4.8" if args.database_engine=="MARIADB" else "mysql:8.4.7"
            command("docker","run","-d","--name",container,"-p","127.0.0.1::3306",
                    "-e",prefix+"_ROOT_PASSWORD","-e",prefix+"_DATABASE","-e",prefix+"_USER","-e",prefix+"_PASSWORD",image,env=environment)
            created = True
            for _ in range(60):
                health=["docker","exec",container,"healthcheck.sh","--connect","--innodb_initialized"] if args.database_engine=="MARIADB" else ["docker","exec",container,"sh","-c",'MYSQL_PWD="$MYSQL_PASSWORD" mysql -h127.0.0.1 -u "$MYSQL_USER" "$MYSQL_DATABASE" -e "SELECT 1"']
                result = subprocess.run(health,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
                if result.returncode == 0:
                    break
                time.sleep(1)
            else:
                raise RuntimeError(args.database_engine+" did not become ready")
            port = command("docker", "port", container, "3306/tcp").split(":")[-1]
        storage = (resources / "storage.yml").read_text().replace("host: localhost", "host: 127.0.0.1").replace("port: 3306", "port: " + port).replace("database: dexturreputation", "database: reputation").replace("username: dexturreputation", "username: reputation").replace("ssl-mode: verify-full", "ssl-mode: disable")
        storage = storage.replace("type: JSON", "type: " + args.storage_type)
        if args.legacy_mysql:
            mysql = storage.split("  mysql:\n", 1)[1]
            storage = "config-version: 1\n" + "\n".join(line[4:] for line in mysql.splitlines() if line.startswith("    ")) + "\n"
        (config / "storage.yml").write_text(storage)
        java = str(Path(os.environ["JAVA_HOME"]) / "bin/java") if "JAVA_HOME" in os.environ else "java"
        if args.luckperms_jar:
            if not (args.tiers or args.recovery or args.policies or args.administration):
                parser.error("--luckperms-jar requires --tiers, --recovery or --policies")
            shutil.copyfile(args.luckperms_jar,plugins / "LuckPerms.jar")
        phase3_fixture=None
        if args.tiers:
            import uuid
            def offline_id(name):
                raw=bytearray(hashlib.md5(("OfflinePlayer:"+name).encode()).digest());raw[6]=(raw[6]&15)|48;raw[8]=(raw[8]&63)|128;return str(uuid.UUID(bytes=bytes(raw)))
            profile={"playerId":offline_id("Phase4Target"),"scope":"global","community":3,"system":0,"visible":3,"tier":"NEUTRAL","revision":0,"createdAt":"2026-10-19T00:00:00Z","updatedAt":"2026-10-19T00:00:00Z"}
            data={"profiles":[profile],"preferences":[],"votes":[]};version=2
            if args.phase3_json_fixture:
                phase3_fixture=json.loads(args.phase3_json_fixture.read_text())
                if phase3_fixture["formatVersion"]!=3:raise RuntimeError("Expected preserved Phase 3 format 3")
                data=phase3_fixture["data"];data=json.loads(json.dumps(data));data["profiles"].append(profile);data["profiles"].sort(key=lambda row:row["playerId"]+"/"+row["scope"]);version=3
            canonical=json.dumps(data,ensure_ascii=False,separators=(",",":"));snapshot={"formatVersion":version,"generation":0,"data":data,"sha256":hashlib.sha256((str(version)+"\n0\n"+canonical).encode()).hexdigest()}
            fixture_dir=config / "storage/json";fixture_dir.mkdir(parents=True);(fixture_dir / "state.json").write_text(json.dumps(snapshot,ensure_ascii=False,separators=(",",":")))
            if args.storage_type!="JSON":
                (config / "tier-target.yml").write_text(storage);(config / "storage.yml").write_text(storage.replace("type: "+args.storage_type,"type: JSON"));env=os.environ.copy()
                if args.storage_type=="MYSQL":env["DEXTURREPUTATION_DB_PASSWORD"]=password
                with (directory / "offline-tier-import.log").open("w") as out:subprocess.run([java,"-jar",str(artifacts / "DexturReputation-StorageAdmin.jar"),"migrate","--data-directory",str(config),"--target-config",str(config / "tier-target.yml"),"--backups",str(directory / "tier-backups"),"--confirm","STOPPED-ALL-WRITERS"],env=env,stdout=out,stderr=subprocess.STDOUT,check=True)
            tier_text=(resources / "tiers.yml").read_text().replace("minimum: -24","minimum: -1").replace("maximum: 24","maximum: 1").replace("threshold: 25\n    hysteresis: 0","threshold: 2\n    hysteresis: 1").replace("threshold: 100\n    hysteresis: 10","threshold: 4\n    hysteresis: 1").replace("threshold: -25\n    hysteresis: 0","threshold: -2\n    hysteresis: 1")
            (config / "tiers.yml").write_text(tier_text)
            (config / "display.yml").write_text((resources / "display.yml").read_text().replace("mode: DISABLED #","mode: TEAM_IF_FREE #").replace("mode: PLACEHOLDER_ONLY #","mode: DIRECT #").replace("cooldown-seconds: 10","cooldown-seconds: 0"))
            integration=(config / "integrations.yml").read_text().replace("  enabled: false","  enabled: true").replace("  tiers: {}","  tiers: {RECOGNIZED: reputation_recognized, RESPECTED: reputation_respected, SUSPICIOUS: reputation_suspicious}")
            (config / "integrations.yml").write_text(integration)
        phase4_fixture=None
        if args.recovery:
            import uuid
            def offline_id(name):
                raw=bytearray(hashlib.md5(("OfflinePlayer:"+name).encode()).digest());raw[6]=(raw[6]&15)|48;raw[8]=(raw[8]&63)|128;return str(uuid.UUID(bytes=bytes(raw)))
            profile={"playerId":offline_id("Phase5Target"),"scope":"global","community":-101,"system":0,"visible":-101,"tier":"NEUTRAL","revision":0,"createdAt":"2026-10-19T00:00:00Z","updatedAt":"2026-10-19T00:00:00Z"}
            other=dict(profile,playerId=offline_id("Phase5Other"));data={"profiles":[profile,other],"preferences":[],"votes":[]};version=2
            if args.phase4_json_fixture:
                phase4_fixture=json.loads(args.phase4_json_fixture.read_text())
                if phase4_fixture["formatVersion"]!=4:raise RuntimeError("Expected real complete Phase 4 format 4")
                data=json.loads(json.dumps(phase4_fixture["data"]));data["profiles"].extend([profile,other]);data["profiles"].sort(key=lambda row:row["playerId"]+"/"+row["scope"]);version=4
                prior_config=args.phase4_json_fixture.parent.parent.parent / "tiers.yml"
                shutil.copyfile(prior_config,config / "tiers.yml")
                (config / "integrations.yml").write_text((prior_config.parent / "integrations.yml").read_text().replace("  enabled: true","  enabled: false"))
            canonical=json.dumps(data,ensure_ascii=False,separators=(",",":"));snapshot={"formatVersion":version,"generation":0,"data":data,"sha256":hashlib.sha256((str(version)+"\n0\n"+canonical).encode()).hexdigest()}
            fixture_dir=config / "storage/json";fixture_dir.mkdir(parents=True);(fixture_dir / "state.json").write_text(json.dumps(snapshot,ensure_ascii=False,separators=(",",":")))
            if args.storage_type!="JSON":
                (config / "recovery-target.yml").write_text(storage);(config / "storage.yml").write_text(storage.replace("type: "+args.storage_type,"type: JSON"));env=os.environ.copy()
                if args.storage_type=="MYSQL":env["DEXTURREPUTATION_DB_PASSWORD"]=password
                with (directory / "offline-recovery-import.log").open("w") as out:subprocess.run([java,"-jar",str(artifacts / "DexturReputation-StorageAdmin.jar"),"migrate","--data-directory",str(config),"--target-config",str(config / "recovery-target.yml"),"--backups",str(directory / "recovery-backups"),"--confirm","STOPPED-ALL-WRITERS"],env=env,stdout=out,stderr=subprocess.STDOUT,check=True)
            recovery=(resources / "recovery.yml").read_text().replace("minimum-playtime-hours: 30","minimum-playtime-hours: 0").replace("minimum-server-age-days: 7","minimum-server-age-days: 0").replace("inactivity-timeout-seconds: 120","inactivity-timeout-seconds: 2").replace("minimum-meaningful-activity: 3","minimum-meaningful-activity: 1").replace("eligible-day-active-seconds: 300","eligible-day-active-seconds: 1").replace("persistence-seconds: 30","persistence-seconds: 1").replace("required: 18000","required: 3").replace("required: 36000","required: 2").replace("required: 604800","required: 2")
            recovery=recovery.replace("type: LOGIN_DAYS\n            required: 3","type: PLAYTIME\n            required: 2")
            recovery=recovery.replace("          - id: stabilization_active","          - id: first_login\n            type: LOGIN_DAYS\n            required: 1\n            names: {ru_RU: 'День входа', en_US: 'Login day'}\n            descriptions: {ru_RU: 'Подтверждённый вход и активность', en_US: 'Verified login and activity'}\n          - id: stabilization_active")
            recovery=recovery.replace("          - id: restoration_active\n            type: ACTIVE_PLAYTIME\n            required: 3","          - id: restoration_active\n            type: EXTERNAL\n            provider: runtime-qa\n            provider-version: 1\n            required: 1")
            recovery=recovery.replace("          - id: restoration_wait","          - id: no_abuse\n            type: NO_CONFIRMED_ABUSE\n            required: 1\n            names: {ru_RU: 'Нет подтверждённых нарушений', en_US: 'No confirmed abuse'}\n            descriptions: {ru_RU: 'Подтверждение внутренней модели', en_US: 'Verified internal signal'}\n          - id: restoration_wait")
            if args.recovery_disabled:recovery=recovery.replace("enabled: true","enabled: false",1)
            (config / "recovery.yml").write_text(recovery)
        phase5_fixture=None
        if args.policies or args.administration:
            import uuid
            def offline_id(name):
                raw=bytearray(hashlib.md5(("OfflinePlayer:"+name).encode()).digest());raw[6]=(raw[6]&15)|48;raw[8]=(raw[8]&63)|128;return str(uuid.UUID(bytes=bytes(raw)))
            data={"profiles":[],"preferences":[],"votes":[]};version=2
            if args.phase5_json_fixture:
                phase5_fixture=json.loads(args.phase5_json_fixture.read_text())
                if phase5_fixture["formatVersion"]!=5:raise RuntimeError("Expected genuine Phase 5 format 5")
                data=json.loads(json.dumps(phase5_fixture["data"]));version=5
                old_config=args.phase5_json_fixture.parent.parent.parent
                for name in ("tiers.yml","recovery.yml","trust.yml","anti-abuse.yml","display.yml","gui.yml","integrations.yml"):
                    if (old_config/name).is_file():shutil.copyfile(old_config/name,config/name)
            if args.administration and args.phase6_json_fixture:
                fixture=json.loads(args.phase6_json_fixture.read_text())
                if fixture["formatVersion"]!=6:raise RuntimeError("Expected complete Phase 6 format 6")
                data=json.loads(json.dumps(fixture["data"]));version=6
                old_config=args.phase6_json_fixture.parent.parent.parent
                for name in ("tiers.yml","recovery.yml","trust.yml","display.yml","gui.yml","integrations.yml"):
                    if (old_config/name).is_file():shutil.copyfile(old_config/name,config/name)
            data["profiles"].sort(key=lambda row:row["playerId"]+"/"+row["scope"])
            canonical=json.dumps(data,ensure_ascii=False,separators=(",",":"));snapshot={"formatVersion":version,"generation":0,"data":data,"sha256":hashlib.sha256((str(version)+"\n0\n"+canonical).encode()).hexdigest()}
            fixture_dir=config/"storage/json";fixture_dir.mkdir(parents=True);(fixture_dir/"state.json").write_text(json.dumps(snapshot,ensure_ascii=False,separators=(",",":")))
            if args.storage_type!="JSON":
                target=config/"phase6-target.yml";target.write_text(storage);(config/"storage.yml").write_text(storage.replace("type: "+args.storage_type,"type: JSON"));env=os.environ.copy()
                if args.storage_type=="MYSQL":env["DEXTURREPUTATION_DB_PASSWORD"]=password
                with (directory/"offline-phase5-import.log").open("w") as output:subprocess.run([java,"-jar",str(artifacts/"DexturReputation-StorageAdmin.jar"),"migrate","--data-directory",str(config),"--target-config",str(target),"--backups",str(directory/"policy-backups"),"--confirm","STOPPED-ALL-WRITERS"],env=env,stdout=output,stderr=subprocess.STDOUT,check=True)
            (config/"features.yml").write_text("config-version: 1\nhealth: {enabled: true}\n")
            if args.luckperms_jar:
                integration=(config/"integrations.yml").read_text().replace("  enabled: false","  enabled: true").replace("  tiers: {}","  tiers: {RECOGNIZED: reputation_recognized, RESPECTED: reputation_respected, SUSPICIOUS: reputation_suspicious}")
                (config/"integrations.yml").write_text(integration)
        phase6_fixture=None
        if args.administration:
            if args.phase6_json_fixture:
                phase6_fixture=json.loads(args.phase6_json_fixture.read_text())
                if phase6_fixture["formatVersion"]!=6:raise RuntimeError("Expected genuine Phase 6 format 6")
                # The preceding optional Phase 5 seed is empty in this workflow. Replace that seed before import.
            protection=(resources/"anti-abuse.yml").read_text().replace("enabled: true","enabled: false").replace("voting-weight:\n  enabled: false","voting-weight:\n  enabled: true").replace("base: {like: 1.0, dislike: 1.0}","base: {like: 0.35, dislike: 0.35}").replace("burst-protection:\n  enabled: false","burst-protection:\n  enabled: true").replace("suspicious-count: 10","suspicious-count: 2")
            (config/"anti-abuse.yml").write_text(protection)
            (config/"administration.yml").write_text((resources/"administration.yml").read_text().replace("confirmation-threshold: 10","confirmation-threshold: 1").replace("batch-size: 10","batch-size: 1"))
        legacy_fixture = None
        if args.phase2_json_fixture:
            legacy_fixture = json.loads(args.phase2_json_fixture.read_text())
            if legacy_fixture["formatVersion"] != 2 or not legacy_fixture["data"]["votes"] or any(v["calculationVersion"] != 1 for v in legacy_fixture["data"]["votes"]):
                raise RuntimeError("Expected a genuine Phase 2 fixture with version-1 votes")
            fixture_dir = config / "storage/json"
            fixture_dir.mkdir(parents=True)
            shutil.copyfile(args.phase2_json_fixture, fixture_dir / "state.json")
            if args.storage_type != "JSON":
                (config / "upgrade-target.yml").write_text(storage)
                (config / "storage.yml").write_text(storage.replace("type: " + args.storage_type, "type: JSON"))
                upgrade_environment = os.environ.copy()
                if args.storage_type == "MYSQL":
                    upgrade_environment["DEXTURREPUTATION_DB_PASSWORD"] = password
                with (directory / "offline-phase2-import.log").open("w") as output:
                    subprocess.run([java, "-jar", str(artifacts / "DexturReputation-StorageAdmin.jar"), "migrate", "--data-directory", str(config), "--target-config", str(config / "upgrade-target.yml"), "--backups", str(directory / "upgrade-backups"), "--confirm", "STOPPED-ALL-WRITERS", "--language", "en_US"], env=upgrade_environment, stdout=output, stderr=subprocess.STDOUT, check=True)
        launch = [java, "-Xms512m", "-Xmx2g", "-Dterminal.jline=false", "-Dterminal.ansi=false"]
        trust = Path("/etc/ssl/certs/java/cacerts")
        if trust.exists():
            launch.append("-Djavax.net.ssl.trustStore=" + str(trust))
        proxy = urllib.parse.urlsplit(os.environ.get("HTTPS_PROXY", ""))
        if proxy.hostname:
            for protocol in ("http", "https"):
                launch.extend([f"-D{protocol}.proxyHost={proxy.hostname}", f"-D{protocol}.proxyPort={proxy.port or 80}"])
        launch.append("-Ddextur.probe.storage=" + args.storage_type)
        if args.administration:launch.append("-Ddextur.probe.administration=true")
        if args.policies:launch.append("-Ddextur.probe.policies=true")
        if args.recovery:
            launch.append("-Ddextur.probe.recovery=true")
            if args.recovery_disabled:launch.append("-Ddextur.probe.recovery-disabled=true")
        if args.tiers:
            launch.append("-Ddextur.probe.tiers=true")
        if args.voting:
            launch.append("-Ddextur.probe.voting=true")
        if args.protection:
            launch.append("-Ddextur.probe.protection=true")
        if legacy_fixture:
            target = legacy_fixture["data"]["votes"][0]["target"]
            count = sum(v["target"] == target for v in legacy_fixture["data"]["votes"])
            score = next(p["visible"] for p in legacy_fixture["data"]["profiles"] if p["playerId"] == target and p["scope"] == "global")
            launch.extend(["-Ddextur.probe.legacy-target=" + target, "-Ddextur.probe.legacy-count=" + str(count), "-Ddextur.probe.legacy-score=" + str(score)])
        launch += ["-jar", "paper.jar", "--nogui"]
        server_environment = os.environ.copy()
        if args.storage_type == "MYSQL":
            server_environment["DEXTURREPUTATION_DB_PASSWORD"] = password
        else:
            server_environment.pop("DEXTURREPUTATION_DB_PASSWORD", None)
        runs = 3 if args.placeholderapi_jar else 2
        for run in range(1, runs + 1):
            if run == 3:
                shutil.copyfile(args.placeholderapi_jar, plugins / "PlaceholderAPI.jar")
            log = directory / f"smoke-{run}.log"
            clients = []
            try:
                if args.voting or args.protection or args.tiers or args.recovery or args.policies or args.administration:
                    import sys
                    for username in (("Phase7Admin","Phase7Voter","Phase7Target") if args.administration else ("Phase6Admin","Phase6Voter","Phase6Target") if args.policies else ("Phase5Target", "Phase5Other", "Phase5Provider") if args.recovery else ("Phase4A", "Phase4B", "Phase4Target") if args.tiers else ("Phase3A", "Phase3B", "Phase3Target") if args.protection else ("Phase2Voter", "Phase2Target")):
                        client_log = (directory / f"client-{username}-{run}.log").open("w")
                        client = subprocess.Popen([sys.executable, str(ROOT / "scripts/paper-test-client.py"), "--username", username]+(["--walk"] if args.recovery and username=="Phase5Target" else []), stdout=client_log, stderr=subprocess.STDOUT)
                        clients.append((client, client_log))
                with log.open("w") as output:
                    result = subprocess.run(launch, cwd=directory, env=server_environment, stdin=subprocess.DEVNULL, stdout=output, stderr=subprocess.STDOUT, timeout=180)
            finally:
                for client, client_log in clients:
                    if client.poll() is None:
                        client.terminate()
                    client.wait(timeout=5)
                    client_log.close()
            text = log.read_text()
            if result.returncode != 0 or "PROBE_PASS:" not in text or "PROBE_FAIL:" in text:
                raise RuntimeError(f"Paper run {run} failed; inspect {log}")
            if args.administration and "PROBE_ADMINISTRATION_PASS:" not in text:raise RuntimeError(f"Administration verification missing; inspect {log}")
            if args.policies and "PROBE_POLICIES_PASS:" not in text:raise RuntimeError(f"Policy verification missing; inspect {log}")
            if args.policies and run==3 and "PROBE_POLICY_PAPI_PASS:" not in text:raise RuntimeError(f"Policy PAPI verification missing; inspect {log}")
            if args.recovery and "PROBE_RECOVERY_PASS:" not in text:raise RuntimeError(f"Recovery verification missing; inspect {log}")
            if args.recovery and run==3 and "PROBE_RECOVERY_PAPI_PASS:" not in text:raise RuntimeError(f"Recovery PAPI verification missing; inspect {log}")
            if args.tiers and "PROBE_TIERS_PASS:" not in text:
                raise RuntimeError(f"Tier verification missing; inspect {log}")
            if args.tiers and run==3 and "PROBE_TIER_PAPI_PASS:" not in text:
                raise RuntimeError(f"Full PAPI tier verification missing; inspect {log}")
            if args.tiers and args.luckperms_jar and "PROBE_LUCKPERMS_PASS:" not in text:
                raise RuntimeError(f"LuckPerms verification missing; inspect {log}")
            if args.voting and "PROBE_VOTING_PASS:" not in text:
                raise RuntimeError(f"Voting verification missing; inspect {log}")
            if args.protection and "PROBE_PROTECTION_PASS:" not in text:
                raise RuntimeError(f"Protection verification missing; inspect {log}")
            if legacy_fixture and "PROBE_LEGACY_PHASE2_PASS:" not in text:
                raise RuntimeError(f"Legacy Phase 2 verification missing; inspect {log}")
            if run == 3 and "PROBE_PAPI_PASS:" not in text:
                raise RuntimeError(f"PlaceholderAPI verification missing; inspect {log}")
            print(f"Paper 26.2 {args.storage_type} run {run}: PASS ({log})", flush=True)
        if args.tiers:
            snapshot_path=config / "storage/json/state.json"
            if args.storage_type!="JSON":
                exported=storage.replace("type: "+args.storage_type,"type: JSON").replace("directory: storage/json","directory: storage/phase4-export-json");export_config=config / "phase4-export.yml";export_config.write_text(exported)
                with (directory / "offline-phase4-export.log").open("w") as output:subprocess.run([java,"-jar",str(artifacts / "DexturReputation-StorageAdmin.jar"),"migrate","--data-directory",str(config),"--target-config",str(export_config),"--backups",str(directory / "tier-backups"),"--confirm","STOPPED-ALL-WRITERS","--language","en_US"],env=server_environment,stdout=output,stderr=subprocess.STDOUT,check=True)
                snapshot_path=config / "storage/phase4-export-json/state.json"
            current=json.loads(snapshot_path.read_text())
            if current["formatVersion"] not in (4,5,6,7) or not current["data"]["tiers"]["states"]:raise RuntimeError("Phase 4 portable state missing")
            target_id=offline_id("Phase4Target");state=next(row for row in current["data"]["tiers"]["states"] if row["player"]==target_id)
            if state["tier"]!="RESPECTED" or not state["history"]:raise RuntimeError("Durable hysteresis/history missing")
            if args.luckperms_jar and (state["ownedGroups"]!=["reputation_respected"] or state["appliedRevision"]!=state["syncRevision"]):raise RuntimeError("Durable LuckPerms ownership/reconciliation missing")
            if phase3_fixture:
                for collection,key in (("votes",lambda row:row["id"]),("profiles",lambda row:row["playerId"]+"/"+row["scope"]),("preferences",lambda row:row["playerId"])):
                    actual={key(row):row for row in current["data"][collection]}
                    for old in phase3_fixture["data"][collection]:
                        found=actual.get(key(old));expected=dict(old)
                        if collection=="profiles" and found:expected["tier"]=found["tier"]
                        if found!=expected:raise RuntimeError("Phase 3 data changed: "+collection)
                private=current["data"]["protection"]
                for collection,key in (("trusts",lambda row:row["player"]+"/"+row["scope"]),("assessments",lambda row:row["voteId"]),("pairs",lambda row:row["scope"]+"/"+row["voter"]+"/"+row["target"]),("scores",lambda row:row["player"]+"/"+row["scope"]),("audit",lambda row:row["id"])):
                    actual={key(row):row for row in private[collection]}
                    if any(actual.get(key(row))!=row for row in phase3_fixture["data"]["protection"][collection]):raise RuntimeError("Phase 3 private data changed: "+collection)
                print("PHASE3_UPGRADE_PASS: complete real profiles, votes, pending, precision, Trust and audit preserved",flush=True)
            print("PHASE4_CLI_PASS: complete tiers, hysteresis, history, ownership and synchronization state verified",flush=True)
        if args.recovery:
            snapshot_path=config / "storage/json/state.json"
            if args.storage_type!="JSON":
                exported=storage.replace("type: "+args.storage_type,"type: JSON").replace("directory: storage/json","directory: storage/phase5-export-json");export_config=config / "phase5-export.yml";export_config.write_text(exported)
                with (directory / "offline-phase5-export.log").open("w") as output:subprocess.run([java,"-jar",str(artifacts / "DexturReputation-StorageAdmin.jar"),"migrate","--data-directory",str(config),"--target-config",str(export_config),"--backups",str(directory / "recovery-backups"),"--confirm","STOPPED-ALL-WRITERS","--language","en_US"],env=server_environment,stdout=output,stderr=subprocess.STDOUT,check=True)
                snapshot_path=config / "storage/phase5-export-json/state.json"
            current=json.loads(snapshot_path.read_text())
            if current["formatVersion"] not in (5,6,7) or not current["data"]["recovery"]["states"]:raise RuntimeError("Phase 5 portable state missing")
            state=next(row for row in current["data"]["recovery"]["states"] if row["player"]==offline_id("Phase5Target"))
            if not args.recovery_disabled and (len(state["rewards"])!=3 or state["lifetimeReward"]!=30 or state["enrollments"][-1]["status"]!="COMPLETED"):raise RuntimeError("Recovery rewards/progress not preserved")
            if phase4_fixture:
                for name,key in (("profiles",lambda row:row["playerId"]+"/"+row["scope"]),("preferences",lambda row:row["playerId"]),("votes",lambda row:row["id"])):
                    actual={key(row):row for row in current["data"][name]}
                    if any(actual.get(key(row))!=row for row in phase4_fixture["data"][name]):raise RuntimeError("Phase 4 data changed: "+name)
                for name,key in (("trusts",lambda row:row["player"]+"/"+row["scope"]),("pairs",lambda row:row["scope"]+"/"+row["voter"]+"/"+row["target"]),("scores",lambda row:row["player"]+"/"+row["scope"]),("assessments",lambda row:row["voteId"]),("audit",lambda row:row["id"])):
                    actual={key(row):row for row in current["data"]["protection"][name]}
                    if any(actual.get(key(row))!=row for row in phase4_fixture["data"]["protection"][name]):raise RuntimeError("Phase 4 protection changed: "+name)
                actual={row["player"]+"/"+row["scope"]:row for row in current["data"]["tiers"]["states"]}
                if any(actual.get(row["player"]+"/"+row["scope"])!=row for row in phase4_fixture["data"]["tiers"]["states"]):raise RuntimeError("Phase 4 tier history/hysteresis/ownership changed")
                print("PHASE4_UPGRADE_PASS: full real votes, pending, precision, Trust, tier hysteresis/history/ownership and languages preserved",flush=True)
            print("PHASE5_CLI_PASS: complete enrollment, progress, reward ledger, activity and audit verified",flush=True)
        if args.policies:
            snapshot_path=config/"storage/json/state.json"
            if args.storage_type!="JSON":
                exported=storage.replace("type: "+args.storage_type,"type: JSON").replace("directory: storage/json","directory: storage/phase6-export-json");target=config/"phase6-export.yml";target.write_text(exported)
                with (directory/"offline-phase6-export.log").open("w") as output:subprocess.run([java,"-jar",str(artifacts/"DexturReputation-StorageAdmin.jar"),"migrate","--data-directory",str(config),"--target-config",str(target),"--backups",str(directory/"policy-backups"),"--confirm","STOPPED-ALL-WRITERS"],env=server_environment,stdout=output,stderr=subprocess.STDOUT,check=True)
                snapshot_path=config/"storage/phase6-export-json/state.json"
            current=json.loads(snapshot_path.read_text())
            if current["formatVersion"] not in (6,7):raise RuntimeError("Phase 6 portable state missing")
            rows={row["player"]+"/"+row["scope"]:row for row in current["data"]["administration"]["states"]}
            target=rows[offline_id("Phase6Target")+"/global"];voter=rows[offline_id("Phase6Voter")+"/global"]
            if target["contribution"]!=100 or not voter["votingLocked"] or len(target["audit"])<9:raise RuntimeError("Admin ledger/lock missing")
            if phase5_fixture:
                for name,key in (("profiles",lambda row:row["playerId"]+"/"+row["scope"]),("preferences",lambda row:row["playerId"]),("votes",lambda row:row["id"])):
                    actual={key(row):row for row in current["data"][name]}
                    if any(actual.get(key(row))!=row for row in phase5_fixture["data"][name]):raise RuntimeError("Phase 5 data changed: "+name)
                for name,key in (("trusts",lambda row:row["player"]+"/"+row["scope"]),("pairs",lambda row:row["scope"]+"/"+row["voter"]+"/"+row["target"]),("scores",lambda row:row["player"]+"/"+row["scope"]),("assessments",lambda row:row["voteId"]),("audit",lambda row:row["id"])):
                    actual={key(row):row for row in current["data"]["protection"][name]}
                    if any(actual.get(key(row))!=row for row in phase5_fixture["data"]["protection"][name]):raise RuntimeError("Phase 5 private data changed: "+name)
                for collection in ("recovery","tiers"):
                    actual={row["player"]+"/"+row["scope"]:row for row in current["data"][collection]["states"]}
                    for old in phase5_fixture["data"][collection]["states"]:
                        found=actual.get(old["player"]+"/"+old["scope"]);expected=dict(old)
                        if args.luckperms_jar and collection=="tiers" and found:
                            for field in ("appliedRevision","ownedGroups"):expected[field]=found[field]
                        if found!=expected:raise RuntimeError("Phase 5 "+collection+" history changed")
                print("PHASE5_UPGRADE_PASS: all original profiles, votes, pending, Trust, precision, recovery rewards/audit, tier history and languages preserved; LP may reconcile owned sync fields",flush=True)
            print("PHASE6_CLI_PASS: complete administration audit, aggregates, voting lock and portable snapshot verified",flush=True)
            if args.storage_type=="MYSQL":
                import runpy
                cli=runpy.run_path(str(ROOT/"scripts/policy-cli-migrations.py"))
                cli["verify"](directory,storage,java,artifacts,server_environment,container,args.database_engine,snapshot_path,resources)
        if args.administration:
            snapshot_path=config/"storage/json/state.json"
            if args.storage_type!="JSON":
                exported=storage.replace("type: "+args.storage_type,"type: JSON").replace("directory: storage/json","directory: storage/phase7-export-json")
                target=config/"phase7-export.yml";target.write_text(exported)
                with (directory/"offline-phase7-export.log").open("w") as output:subprocess.run([java,"-jar",str(artifacts/"DexturReputation-StorageAdmin.jar"),"migrate","--data-directory",str(config),"--target-config",str(target),"--backups",str(directory/"administration-backups"),"--confirm","STOPPED-ALL-WRITERS"],env=server_environment,stdout=output,stderr=subprocess.STDOUT,check=True)
                snapshot_path=config/"storage/phase7-export-json/state.json"
            current=json.loads(snapshot_path.read_text())
            if current["formatVersion"]!=7 or not current["data"]["investigation"]["records"]:raise RuntimeError("Phase 7 state missing")
            if phase6_fixture:
                for collection,key in (("profiles",lambda row:row["playerId"]+"/"+row["scope"]),("preferences",lambda row:row["playerId"]),("votes",lambda row:row["id"])):
                    actual={key(row):row for row in current["data"][collection]}
                    if any(actual.get(key(row))!=row for row in phase6_fixture["data"][collection]):raise RuntimeError("Phase 6 collection changed: "+collection)
                for name,key in (("trusts",lambda row:row["player"]+"/"+row["scope"]),("pairs",lambda row:row["scope"]+"/"+row["voter"]+"/"+row["target"]),("scores",lambda row:row["player"]+"/"+row["scope"]),("assessments",lambda row:row["voteId"]),("audit",lambda row:row["id"])):
                    actual={key(row):row for row in current["data"]["protection"][name]}
                    if any(actual.get(key(row))!=row for row in phase6_fixture["data"]["protection"][name]):raise RuntimeError("Phase 6 protection changed: "+name)
                for name in ("administration","recovery","tiers"):
                    actual={row["player"]+"/"+row["scope"]:row for row in current["data"][name]["states"]}
                    for old in phase6_fixture["data"][name]["states"]:
                        found=actual.get(old["player"]+"/"+old["scope"]);expected=dict(old)
                        if name=="tiers" and args.luckperms_jar and found:
                            for field in ("appliedRevision","ownedGroups"):expected[field]=found[field]
                        if found!=expected:raise RuntimeError("Phase 6 ledger changed: "+name)
                print("PHASE6_UPGRADE_PASS: complete original public/private/vote/recovery/policy/admin dataset preserved",flush=True)
            print("PHASE7_CLI_PASS: complete moderation, audit, flag resolution and bulk metadata verified",flush=True)
            if args.storage_type=="MYSQL":
                import runpy
                cli=runpy.run_path(str(ROOT/"scripts/policy-cli-migrations.py"))
                cli["verify"](directory,storage,java,artifacts,server_environment,container,args.database_engine,snapshot_path,resources,phase_number=7)
        if legacy_fixture:
            # Paper is stopped: verify the complete old dataset and the new private state through
            # the real standalone CLI, including a Phase 3 SQL -> JSON migration where applicable.
            snapshot_path = config / "storage/json/state.json"
            if args.storage_type != "JSON":
                exported = storage.replace("type: " + args.storage_type, "type: JSON").replace("directory: storage/json", "directory: storage/phase3-export-json")
                export_config = config / "phase3-export.yml"
                export_config.write_text(exported)
                with (directory / "offline-phase3-export.log").open("w") as output:
                    subprocess.run([java, "-jar", str(artifacts / "DexturReputation-StorageAdmin.jar"), "migrate", "--data-directory", str(config), "--target-config", str(export_config), "--backups", str(directory / "upgrade-backups"), "--confirm", "STOPPED-ALL-WRITERS", "--language", "en_US"], env=server_environment, stdout=output, stderr=subprocess.STDOUT, check=True)
                snapshot_path = config / "storage/phase3-export-json/state.json"
            current = json.loads(snapshot_path.read_text())
            for collection, key in (("votes", lambda row: row["id"]), ("profiles", lambda row: (row["playerId"], row["scope"])), ("preferences", lambda row: row["playerId"])):
                actual = {key(row): row for row in current["data"][collection]}
                if any(actual.get(key(row)) != row for row in legacy_fixture["data"][collection]):
                    raise RuntimeError("Phase 2 dataset changed: " + collection)
            private = current["data"]["protection"]
            if current["formatVersion"] not in (3,4,5,6,7) or not private["trusts"] or not private["audit"] or not any(a["status"] == "PENDING" for a in private["assessments"]):
                raise RuntimeError("Complete Phase 3 state was not preserved")
            print("PHASE2_DATA_AND_PHASE3_CLI_PASS: profiles, languages, historical votes, exact weights, Trust, audit and pending", flush=True)
    finally:
        if created:
            subprocess.run(["docker", "rm", "-f", "-v", container], stdout=subprocess.DEVNULL, check=True)


if __name__ == "__main__":
    main()

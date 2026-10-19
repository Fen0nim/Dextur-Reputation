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
    if sum((args.voting,args.protection,args.tiers))>1:
        parser.error("Run --voting, --protection and --tiers separately")
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
    for name in ("DexturReputation.jar", "DexturReputationProbe.jar") + (("DexturReputation-StorageAdmin.jar",) if args.phase2_json_fixture or args.tiers else ()):
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
    if args.voting or args.protection or args.tiers:
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
            if not args.tiers:
                parser.error("--luckperms-jar requires --tiers")
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
                if args.voting or args.protection or args.tiers:
                    import sys
                    for username in (("Phase4A", "Phase4B", "Phase4Target") if args.tiers else ("Phase3A", "Phase3B", "Phase3Target") if args.protection else ("Phase2Voter", "Phase2Target")):
                        client_log = (directory / f"client-{username}-{run}.log").open("w")
                        client = subprocess.Popen([sys.executable, str(ROOT / "scripts/paper-test-client.py"), "--username", username], stdout=client_log, stderr=subprocess.STDOUT)
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
            if current["formatVersion"]!=4 or not current["data"]["tiers"]["states"]:raise RuntimeError("Phase 4 portable state missing")
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
            if current["formatVersion"] not in (3,4) or not private["trusts"] or not private["audit"] or not any(a["status"] == "PENDING" for a in private["assessments"]):
                raise RuntimeError("Complete Phase 3 state was not preserved")
            print("PHASE2_DATA_AND_PHASE3_CLI_PASS: profiles, languages, historical votes, exact weights, Trust, audit and pending", flush=True)
    finally:
        if created:
            subprocess.run(["docker", "rm", "-f", "-v", container], stdout=subprocess.DEVNULL, check=True)


if __name__ == "__main__":
    main()

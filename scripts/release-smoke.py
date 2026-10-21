#!/usr/bin/env python3
"""Paper 26.2/Java 25: clean defaults, four restart cycles, optional dependencies and native restore.

Use only new directories outside the checkout. All SQL schemas/containers belong to this invocation.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import runpy
import secrets
import shutil
import subprocess
import sys
import time
import zipfile
import sqlite3
import urllib.parse

ROOT = Path(__file__).resolve().parents[1]
PAPER_SHA = "3cce0a4922baf79bd8e1275ee5ff9f073f24675770e09b0d4be9442d513e7a6b"
PAPI_SHA = "fde03259f5af6938f3c33eeb4d814000a1adabf1d2304ce14970be81f609a437"
LP_SHA = "09d07b68965717976d2bab2f8d10436676f7c68d90bf6d24628cc0e5228bf406"


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--accept-eula", action="store_true")
    parser.add_argument("--directory", required=True, type=Path)
    parser.add_argument("--storage-type", choices=["JSON", "SQLITE", "MYSQL"], default="JSON")
    parser.add_argument("--database-engine", choices=["MARIADB", "MYSQL"], default="MARIADB")
    parser.add_argument("--paper-jar", required=True, type=Path)
    parser.add_argument("--mojang-cache", required=True, type=Path)
    parser.add_argument("--artifacts-directory", type=Path, default=ROOT / "reputation-paper/build/libs")
    parser.add_argument("--placeholderapi-jar", type=Path)
    parser.add_argument("--luckperms-jar", type=Path)
    parser.add_argument("--historical-data", type=Path, help="Retained genuine plugin data folder (JSON or SQLite)")
    args = parser.parse_args()
    directory = args.directory.resolve()
    if not args.accept_eula:
        parser.error("Explicit Minecraft EULA acceptance is required")
    if directory == ROOT or ROOT in directory.parents or directory.exists() and any(directory.iterdir()):
        parser.error("Use a new/empty directory outside the checkout")
    directory.mkdir(parents=True, exist_ok=True)
    plugins = directory / "plugins"
    plugins.mkdir()
    config = plugins / "DexturReputation"
    artifacts = args.artifacts_directory.resolve()
    proof = {"storage": args.storage_type, "engine": args.database_engine if args.storage_type == "MYSQL" else args.storage_type,
             "papi": bool(args.placeholderapi_jar), "luckperms": bool(args.luckperms_jar), "runs": [], "artifacts": {}}
    for name in ["DexturReputation.jar", "DexturReputation-StorageAdmin.jar", "DexturReputationProbe.jar"]:
        proof["artifacts"][name] = digest(artifacts / name)
        if name != "DexturReputation-StorageAdmin.jar":
            shutil.copy2(artifacts / name, plugins / name)
    assert digest(args.paper_jar) == PAPER_SHA
    shutil.copy2(args.paper_jar, directory / "paper.jar")
    with zipfile.ZipFile(args.paper_jar) as archive:
        mojang_sha, _, mojang_name = archive.read("META-INF/download-context").decode().strip().split("\t")
    assert digest(args.mojang_cache) == mojang_sha
    (directory / "cache").mkdir()
    shutil.copy2(args.mojang_cache, directory / "cache" / mojang_name)
    for supplied, name, expected in [(args.placeholderapi_jar, "PlaceholderAPI.jar", PAPI_SHA), (args.luckperms_jar, "LuckPerms.jar", LP_SHA)]:
        if supplied:
            assert digest(supplied) == expected
            shutil.copy2(supplied, plugins / name)
    (directory / "eula.txt").write_text("eula=true\n")
    (directory / "server.properties").write_text("server-ip=127.0.0.1\nserver-port=25566\nonline-mode=false\nview-distance=2\nsimulation-distance=2\nmax-players=3\nlevel-name=smoke-world\ngenerate-structures=false\n")
    java = str(Path(os.environ["JAVA_HOME"]) / "bin/java")
    environment = os.environ.copy()
    container = "dextur-release-" + secrets.token_hex(6)
    password = secrets.token_urlsafe(32)
    environment.update(MYSQL_ROOT_PASSWORD=password, MYSQL_DATABASE="reputation", MYSQL_USER="reputation", MYSQL_PASSWORD=password,
                       MARIADB_ROOT_PASSWORD=password, MARIADB_DATABASE="reputation", MARIADB_USER="reputation", MARIADB_PASSWORD=password,
                       MYSQL_PWD=password, DEXTURREPUTATION_DB_PASSWORD=password)
    created = False

    def command(*parts, **kwargs):
        return subprocess.check_output(parts, text=True, env=environment, **kwargs).strip()

    def native(parts, output, source=None):
        with output.open("wb") as out, (source.open("rb") if source else open(os.devnull, "rb")) as incoming:
            subprocess.run(["docker", "exec", "-i", "-e", "MYSQL_PWD", container] + parts,
                           env=environment, stdin=incoming, stdout=out, stderr=subprocess.PIPE, check=True, timeout=120)

    def verify_storage(label):
        with (directory / (label + "-verify.log")).open("w") as output:
            subprocess.run([java, "-jar", str(artifacts / "DexturReputation-StorageAdmin.jar"), "verify", "--data-directory", str(config), "--confirm", "STOPPED-ALL-WRITERS", "--language", "en_US"], env=environment, stdout=output, stderr=subprocess.STDOUT, check=True)

    def export_snapshot(label):
        original = (config / "storage.yml").read_bytes()
        target = config / (label + "-target.yml")
        text = original.decode().replace("type: MYSQL", "type: JSON").replace("type: SQLITE", "type: JSON").replace("directory: storage/json", "directory: storage/" + label)
        target.write_text(text)
        try:
            with (directory / (label + "-export.log")).open("w") as output:
                subprocess.run([java, "-jar", str(artifacts / "DexturReputation-StorageAdmin.jar"), "migrate", "--data-directory", str(config), "--target-config", str(target), "--backups", str(directory / "portable-backups"), "--confirm", "STOPPED-ALL-WRITERS"], env=environment, stdout=output, stderr=subprocess.STDOUT, check=True)
            return json.loads((config / "storage" / label / "state.json").read_text())["data"]
        finally:
            (config / "storage.yml").write_bytes(original)

    try:
        if args.storage_type == "MYSQL":
            prefix = args.database_engine
            image = "mariadb:11.4.8" if prefix == "MARIADB" else "mysql:8.4.7"
            command("docker", "run", "-d", "--name", container, "-p", "127.0.0.1::3306", "-e", prefix + "_ROOT_PASSWORD", "-e", prefix + "_DATABASE", "-e", prefix + "_USER", "-e", prefix + "_PASSWORD", image)
            created = True
            client = "mariadb" if prefix == "MARIADB" else "mysql"
            dump = "mariadb-dump" if prefix == "MARIADB" else "mysqldump"
            for attempt in range(90):
                result = subprocess.run(["docker", "exec", "-e", "MYSQL_PWD", container, client, "-ureputation", "reputation", "-e", "SELECT 1"], env=environment, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
                if result.returncode == 0:
                    break
                time.sleep(1)
            else:
                raise RuntimeError("Disposable SQL server did not become ready")
            port = command("docker", "port", container, "3306/tcp").split(":")[-1]
        if args.storage_type != "JSON":
            # Newly selected backend; all other files are genuinely generated by the plugin.
            config.mkdir()
            storage = (ROOT / "reputation-paper/src/main/resources/storage.yml").read_text().replace("type: JSON", "type: " + args.storage_type)
            if args.storage_type == "MYSQL":
                storage = storage.replace("host: localhost", "host: 127.0.0.1").replace("port: 3306", "port: " + port).replace("database: dexturreputation", "database: reputation").replace("username: dexturreputation", "username: reputation").replace("ssl-mode: verify-full", "ssl-mode: disable")
            (config / "storage.yml").write_text(storage)
        historical = None
        if args.historical_data:
            source=args.historical_data.resolve()
            if (source / "storage/json/state.json").exists():
                document=json.loads((source / "storage/json/state.json").read_text()); historical=document["data"]
                proof["historical_format"]=document["formatVersion"]
            else:
                database=source / "storage/reputation.sqlite"
                with sqlite3.connect("file:"+str(database)+"?mode=ro",uri=True) as c:
                    proof["historical_schema"]=c.execute("PRAGMA user_version").fetchone()[0]
                    fields=["playerId","scope","community","system","visible","tier","revision","createdAt","updatedAt"]
                    historical={"profiles":[dict(zip(fields,row)) for row in c.execute("SELECT * FROM dr_profiles")],"preferences":[dict(zip(["playerId","language","updatedAt"],row)) for row in c.execute("SELECT * FROM dr_player_preferences")]}
            desired=(config / "storage.yml").read_bytes() if config.exists() else (ROOT / "reputation-paper/src/main/resources/storage.yml").read_bytes()
            if config.exists(): shutil.rmtree(config) # Newly created, empty QA choice only.
            shutil.copytree(source,config)
            if args.storage_type=="MYSQL":
                # Genuine JSON schema/format 7 source; bootstrap through the real final offline CLI.
                (config / "storage.yml").write_text((source / "storage.yml").read_text().replace("type: MYSQL","type: JSON"))
                target=config / "historical-sql-target.yml";target.write_bytes(desired)
                with (directory / "historical-bootstrap.log").open("w") as out:
                    subprocess.run([java,"-jar",str(artifacts / "DexturReputation-StorageAdmin.jar"),"migrate","--data-directory",str(config),"--target-config",str(target),"--backups",str(directory / "historical-backups"),"--confirm","STOPPED-ALL-WRITERS"],env=environment,stdout=out,stderr=subprocess.STDOUT,check=True)
            else: (config / "storage.yml").write_bytes(desired)
            # Keep original historical files untouched; isolate the health adapter's QA on/off sequence.
            (config / "features.yml").write_text("config-version: 1\nhealth: {enabled: false}\n")
            proof["historical_source"]=str(source)

        def retained(old,new,path="data"):
            if isinstance(old,dict):
                for key,value in old.items(): retained(value,new[key],path+"/"+key)
            elif isinstance(old,list):
                for value in old:
                    assert value in new, "Historical record changed: "+path
            else: assert old==new, "Historical value changed: "+path

        for run in range(1, 5):
            if run == 2:
                p = config / "display.yml"
                p.write_text(p.read_text().replace("mode: PLACEHOLDER_ONLY", "mode: DISABLED"))
            if run == 3:
                verify_storage("before-backup")
                backup = directory / "native-backup"
                backup.mkdir()
                if args.storage_type in ["JSON", "SQLITE"]:
                    wal = config / "storage/reputation.sqlite-wal"
                    assert not wal.exists() or wal.stat().st_size == 0, "Uncheckpointed WAL: refuse unsafe copy"
                    shutil.copytree(config, backup / "plugin-data")
                    config.rename(directory / "retained-before-restore")
                    shutil.copytree(backup / "plugin-data", config)
                else:
                    before = export_snapshot("release-before-restore")
                    native([dump, "-ureputation", "--single-transaction", "--skip-lock-tables", "--no-tablespaces", "--skip-comments", "reputation"], backup / "database.sql")
                    native([client, "-uroot", "-e", "CREATE DATABASE reputation_restore; GRANT ALL ON reputation_restore.* TO 'reputation'@'%';"], backup / "create-clean-database.log")
                    native([client, "-ureputation", "reputation_restore"], backup / "restore.log", backup / "database.sql")
                    p = config / "storage.yml"
                    p.write_text(p.read_text().replace("database: reputation", "database: reputation_restore"))
                    after = export_snapshot("release-after-restore")
                    assert before == after, "Native restore differs from complete portable source"
                    proof["native_dump_tool"] = command("docker", "exec", container, dump, "--version")
                    proof["native_restore_snapshot_equality"] = True
                verify_storage("after-restore")
                proof["backup_checksums"] = {str(p.relative_to(backup)): digest(p) for p in backup.rglob("*") if p.is_file()}
                if args.luckperms_jar:
                    shutil.copytree(plugins / "LuckPerms", backup / "LuckPerms")
                (config / "features.yml").write_text("config-version: 1\nhealth: {enabled: true}\n")
                p = config / "display.yml"
                p.write_text(p.read_text().replace("mode: DISABLED # DISABLED, DIRECT", "mode: DIRECT # DISABLED, DIRECT").replace("mode: DISABLED # DISABLED or TEAM_IF_FREE", "mode: TEAM_IF_FREE # DISABLED or TEAM_IF_FREE"))
                if args.luckperms_jar:
                    p = config / "integrations.yml"
                    p.write_text(p.read_text().replace("  enabled: false", "  enabled: true").replace("  create-groups: false", "  create-groups: true").replace("  tiers: {}", "  tiers: {RESPECTED: reputation_respected}"))
            if run == 4:
                p = config / "display.yml"
                p.write_text(p.read_text().replace("mode: DIRECT # DISABLED, DIRECT", "mode: PLACEHOLDER_ONLY # DISABLED, DIRECT"))
            clients = []
            try:
                for username in ["ReleaseAdmin", "ReleaseTarget", "ReleaseUser"]:
                    output = (directory / f"client-{username}-{run}.log").open("w")
                    process = subprocess.Popen([sys.executable, str(ROOT / "scripts/paper-test-client.py"), "--username", username], stdout=output, stderr=subprocess.STDOUT)
                    clients.append((process, output))
                launch = [java, "-Xms256m", "-Xmx1g", "-Ddextur.probe.release=true", "-Ddextur.probe.release-run=" + str(run), "-Ddextur.probe.storage=" + args.storage_type,
                          "-Ddextur.probe.expect-papi=" + str(bool(args.placeholderapi_jar)).lower(), "-Ddextur.probe.expect-lp=" + str(bool(args.luckperms_jar)).lower(), "-jar", "paper.jar", "--nogui"]
                platform = ["-Dterminal.jline=false", "-Dterminal.ansi=false"]
                trust = Path("/etc/ssl/certs/java/cacerts")
                if trust.exists(): platform.append("-Djavax.net.ssl.trustStore=" + str(trust))
                proxy = urllib.parse.urlsplit(os.environ.get("HTTPS_PROXY", ""))
                if proxy.hostname:
                    for protocol in ("http", "https"):
                        platform.extend([f"-D{protocol}.proxyHost={proxy.hostname}", f"-D{protocol}.proxyPort={proxy.port or 80}"])
                launch[1:1] = platform
                log = directory / f"smoke-{run}.log"
                with log.open("w") as output:
                    result = subprocess.run(launch, cwd=directory, env=environment, stdin=subprocess.DEVNULL, stdout=output, stderr=subprocess.STDOUT, timeout=180)
            finally:
                for process, output in clients:
                    if process.poll() is None:
                        process.terminate()
                    process.wait(timeout=5)
                    output.close()
            text = log.read_text()
            assert result.returncode == 0 and "PROBE_FAIL:" not in text, str(log)
            for marker in ["PROBE_RELEASE_PASS:", "PROBE_RELEASE_CLEANUP_PASS:", "PROBE_PASS:", "All dimensions are saved", "STOPPED: services unregistered"]:
                assert marker in text, (str(log), marker)
            assert "Shutdown deadline exceeded" not in text
            if args.storage_type=="MYSQL":
                sessions=command("docker","exec","-e","MYSQL_PWD",container,client,"-uroot","--batch","--skip-column-names","-e","SELECT COUNT(*) FROM information_schema.processlist WHERE USER='reputation'")
                assert sessions=="0", "Application SQL sessions remained after shutdown"
                proof["application_connections_after_shutdown"]=0
            if historical is not None and run==1:
                if args.storage_type=="JSON": upgraded=json.loads((config / "storage/json/state.json").read_text())["data"]
                else: upgraded=export_snapshot("historical-verification")
                retained(historical,upgraded)
                proof["historical_all_original_records_retained"]=True
            proof["runs"].append({"number": run, "log": str(log), "restored": run >= 3, "status": "PASS"})
            for name, expected in proof["artifacts"].items():
                assert digest(artifacts / name) == expected
            (directory / "release-proof.json").write_text(json.dumps(proof, indent=2))
            print("RELEASE_PAPER_PASS", proof["engine"], "PAPI=" + str(proof["papi"]), "LP=" + str(proof["luckperms"]), "run", run, flush=True)
        print("RELEASE_CASE_PASS: clean installation, four JVM cycles, native restore, dependencies and display modes", flush=True)
    finally:
        if created:
            subprocess.run(["docker", "rm", "-f", "-v", container], stdout=subprocess.DEVNULL, check=True)


if __name__ == "__main__":
    main()

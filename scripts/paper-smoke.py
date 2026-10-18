#!/usr/bin/env python3
"""Real Paper 26.2 smoke/restart test. Requires Java 25, Docker and explicit EULA acceptance."""
import argparse
import hashlib
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
    parser.add_argument("--storage-type", choices=("JSON", "SQLITE", "MYSQL"), default="JSON")
    parser.add_argument("--legacy-mysql", action="store_true", help="Test unchanged Phase 1 storage.yml")
    parser.add_argument("--mojang-cache", type=Path, help="Reuse a cached Mojang server jar after checking Paper's embedded SHA-256")
    parser.add_argument("--voting", action="store_true", help="Exercise Phase 2 with two local protocol 776 clients and relaxed QA eligibility/cooldowns")
    args = parser.parse_args()
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
    for name in ("DexturReputation.jar", "DexturReputationProbe.jar"):
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
    (directory / "server.properties").write_text("server-ip=127.0.0.1\nserver-port=25566\nonline-mode=false\nview-distance=2\nsimulation-distance=2\nmax-players=2\nlevel-name=smoke-world\ngenerate-structures=false\n")
    plugins = directory / "plugins"
    config = plugins / "DexturReputation"
    config.mkdir(parents=True)
    for name in ("DexturReputation.jar", "DexturReputationProbe.jar"):
        shutil.copyfile(artifacts / name, plugins / name)
    resources = ROOT / "reputation-paper/src/main/resources"
    for name in ("config.yml", "integrations.yml"):
        shutil.copyfile(resources / name, config / name)
    if args.voting:
        voting = (resources / "voting.yml").read_text().replace("minimum-playtime-seconds: 108000", "minimum-playtime-seconds: 0").replace("minimum-account-age-seconds: 604800", "minimum-account-age-seconds: 0").replace("cooldown-seconds: 7200", "cooldown-seconds: 0").replace("base-daily-limit: 3", "base-daily-limit: 1000")
        (config / "voting.yml").write_text(voting)
    container = "dextur-smoke-" + secrets.token_hex(6)
    password = secrets.token_urlsafe(32)
    environment = os.environ.copy()
    environment.update(MARIADB_ROOT_PASSWORD=password, MARIADB_DATABASE="reputation", MARIADB_USER="reputation", MARIADB_PASSWORD=password)
    created = False
    try:
        port = "3306"
        if args.storage_type == "MYSQL":
            command("docker", "run", "-d", "--name", container, "-p", "127.0.0.1::3306",
                    "-e", "MARIADB_ROOT_PASSWORD", "-e", "MARIADB_DATABASE", "-e", "MARIADB_USER", "-e", "MARIADB_PASSWORD",
                    "mariadb:11.4.8", env=environment)
            created = True
            for _ in range(60):
                result = subprocess.run(["docker", "exec", container, "healthcheck.sh", "--connect", "--innodb_initialized"], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
                if result.returncode == 0:
                    break
                time.sleep(1)
            else:
                raise RuntimeError("MariaDB did not become ready")
            port = command("docker", "port", container, "3306/tcp").split(":")[-1]
        storage = (resources / "storage.yml").read_text().replace("host: localhost", "host: 127.0.0.1").replace("port: 3306", "port: " + port).replace("database: dexturreputation", "database: reputation").replace("username: dexturreputation", "username: reputation").replace("ssl-mode: verify-full", "ssl-mode: disable")
        storage = storage.replace("type: JSON", "type: " + args.storage_type)
        if args.legacy_mysql:
            mysql = storage.split("  mysql:\n", 1)[1]
            storage = "config-version: 1\n" + "\n".join(line[4:] for line in mysql.splitlines() if line.startswith("    ")) + "\n"
        (config / "storage.yml").write_text(storage)
        java = str(Path(os.environ["JAVA_HOME"]) / "bin/java") if "JAVA_HOME" in os.environ else "java"
        launch = [java, "-Xms512m", "-Xmx2g", "-Dterminal.jline=false", "-Dterminal.ansi=false"]
        trust = Path("/etc/ssl/certs/java/cacerts")
        if trust.exists():
            launch.append("-Djavax.net.ssl.trustStore=" + str(trust))
        proxy = urllib.parse.urlsplit(os.environ.get("HTTPS_PROXY", ""))
        if proxy.hostname:
            for protocol in ("http", "https"):
                launch.extend([f"-D{protocol}.proxyHost={proxy.hostname}", f"-D{protocol}.proxyPort={proxy.port or 80}"])
        launch.append("-Ddextur.probe.storage=" + args.storage_type)
        if args.voting:
            launch.append("-Ddextur.probe.voting=true")
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
                if args.voting:
                    import sys
                    for username in ("Phase2Voter", "Phase2Target"):
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
            if args.voting and "PROBE_VOTING_PASS:" not in text:
                raise RuntimeError(f"Voting verification missing; inspect {log}")
            if run == 3 and "PROBE_PAPI_PASS:" not in text:
                raise RuntimeError(f"PlaceholderAPI verification missing; inspect {log}")
            print(f"Paper 26.2 {args.storage_type} run {run}: PASS ({log})", flush=True)
    finally:
        if created:
            subprocess.run(["docker", "rm", "-f", container], stdout=subprocess.DEVNULL, check=True)


if __name__ == "__main__":
    main()

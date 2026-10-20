"""Standalone offline CLI matrix on a stopped, isolated QA SQL server. Never used by the plugin."""
import json
from pathlib import Path
import shutil
import sqlite3
import subprocess


def verify(directory, storage, java, artifacts, environment, container, engine, snapshot_path, resources):
    original = json.loads(snapshot_path.read_text())["data"]
    base = storage.replace("type: MYSQL", "type: JSON")
    executable = "mariadb" if engine == "MARIADB" else "mysql"
    variable = "MARIADB_ROOT_PASSWORD" if engine == "MARIADB" else "MYSQL_ROOT_PASSWORD"

    def sql(statement):
        shell = 'MYSQL_PWD="$' + variable + '" ' + executable + ' -uroot --batch --skip-column-names -e "$1"'
        return subprocess.check_output(["docker", "exec", container, "sh", "-c", shell, "qa", statement], text=True).strip()

    evidence = []
    for source in ("JSON", "SQLITE", "MYSQL"):
        for target in ("JSON", "SQLITE", "MYSQL"):
            if source == target:
                continue
            work = directory / "cli-migrations" / (source + "-" + target)
            work.mkdir(parents=True)
            for name in ("config.yml", "integrations.yml", "lang/ru_RU.yml", "lang/en_US.yml"):
                (work / name).parent.mkdir(parents=True, exist_ok=True)
                shutil.copyfile(resources / name, work / name)

            def config(backend, phase):
                schema = "phase6_" + source.lower() + "_" + target.lower() + "_" + phase
                if backend == "MYSQL":
                    sql("CREATE DATABASE " + schema + "; GRANT ALL ON " + schema + ".* TO 'reputation'@'%';")
                return (base.replace("type: JSON", "type: " + backend)
                        .replace("directory: storage/json", "directory: storage/" + phase + "-json")
                        .replace("file: storage/reputation.sqlite", "file: storage/" + phase + ".sqlite")
                        .replace("database: reputation", "database: " + schema))

            seed = work / "storage/seed-json/state.json"
            seed.parent.mkdir(parents=True)
            shutil.copyfile(snapshot_path, seed)
            (work / "storage.yml").write_text(config("JSON", "seed"))

            def migrate(configuration, label):
                destination = work / (label + ".yml")
                destination.write_text(configuration)
                with (work / (label + ".log")).open("w") as output:
                    subprocess.run([java, "-jar", str(artifacts / "DexturReputation-StorageAdmin.jar"), "migrate",
                                    "--data-directory", str(work), "--target-config", str(destination),
                                    "--backups", str(work / (label + "-backups")),
                                    "--confirm", "STOPPED-ALL-WRITERS", "--language", "en_US"],
                                   env=environment, stdout=output, stderr=subprocess.STDOUT, check=True)

            if source != "JSON":
                migrate(config(source, "source"), "bootstrap")
            migrate(config(target, "destination"), "migration")
            if target == "JSON":
                inspected = work / "storage/destination-json/state.json"
            else:
                # Reopen the final SQL backend in a new JVM and independently export/read it.
                migrate(config("JSON", "inspection"), "inspection")
                inspected = work / "storage/inspection-json/state.json"
            if json.loads(inspected.read_text())["data"] != original:
                raise RuntimeError("CLI full snapshot mismatch: " + source + " -> " + target)
            if seed.read_bytes() != snapshot_path.read_bytes():
                raise RuntimeError("CLI modified/deleted the original JSON source")
            if source == "SQLITE":
                with sqlite3.connect("file:" + str(work / "storage/source.sqlite") + "?mode=ro", uri=True) as db:
                    if db.execute("SELECT COUNT(*) FROM dr_profiles").fetchone()[0] != len(original["profiles"]):
                        raise RuntimeError("CLI removed SQLite source rows")
            if source == "MYSQL":
                schema = "phase6_" + source.lower() + "_" + target.lower() + "_source"
                count = int(sql("SELECT COUNT(*) FROM " + schema + ".dr_profiles"))
                if count != len(original["profiles"]):
                    raise RuntimeError("CLI removed SQL source rows")
            evidence.append({"from": source, "to": target, "status": "PASS", "directory": str(work)})
    (directory / "phase6-six-cli-directions.json").write_text(json.dumps(evidence, indent=2))
    print("PHASE6_SIX_CLI_DIRECTIONS_PASS: six standalone migrations, fresh-JVM verification and source retention on " + engine, flush=True)

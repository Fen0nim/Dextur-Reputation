#!/usr/bin/env python3
"""Paper smoke with an actual V1 SQL schema and representative controlled data.

Delegates the existing legacy-config workflow; owns only its newly created disposable container.
No production code, historical migration, or existing smoke helper is modified.
"""
import hashlib
import json
import os
from pathlib import Path
import runpy
import subprocess
import sys

ROOT=Path(__file__).resolve().parents[1]
PLAYER='1222bc3d-d4e5-4678-9abc-1234567890ab'
OTHER='55555555-5555-4555-8555-555555555555'
STAMP='2026-10-18T08:30:00.123456Z'
EXPECTED={'profiles':[{'playerId':PLAYER,'scope':'global','community':0,'system':0,'visible':0,'tier':'NEUTRAL','revision':0,'createdAt':STAMP,'updatedAt':STAMP},{'playerId':OTHER,'scope':'global','community':12,'system':-19,'visible':-7,'tier':'NEUTRAL','revision':4,'createdAt':STAMP,'updatedAt':STAMP},{'playerId':OTHER,'scope':'other','community':0,'system':0,'visible':0,'tier':'NEUTRAL','revision':0,'createdAt':STAMP,'updatedAt':STAMP}], 'preferences':[{'playerId':PLAYER,'language':'en_US','updatedAt':STAMP},{'playerId':OTHER,'language':'ru_RU','updatedAt':STAMP}]}


def option(name,default=None):
    return sys.argv[sys.argv.index(name)+1] if name in sys.argv else default


def main():
    if '--legacy-mysql' not in sys.argv or option('--storage-type')!='MYSQL':raise SystemExit('Requires --legacy-mysql --storage-type MYSQL and the normal isolated smoke arguments')
    directory=Path(option('--directory')).resolve();artifacts=Path(option('--artifacts-directory',str(ROOT/'reputation-paper/build/libs'))).resolve()
    engine=option('--database-engine','MARIADB');client='mariadb' if engine=='MARIADB' else 'mysql'
    namespace=runpy.run_path(str(ROOT/'scripts/paper-smoke.py'));original=namespace['command'];bindings={};container=None;seeded=False;verified=False
    migration=(ROOT/'reputation-storage/src/main/resources/db/V1__foundation.sql').read_bytes();checksum=hashlib.sha256(migration).hexdigest()

    def sql(statement):
        environment=bindings.copy();environment['MYSQL_PWD']=bindings['MYSQL_PASSWORD']
        return subprocess.check_output(['docker','exec','-i','-e','MYSQL_PWD',container,client,'-ureputation','--batch','--skip-column-names','reputation'],input=statement,text=True,env=environment).strip()

    def seed():
        nonlocal seeded
        assert container.startswith('dextur-smoke-') and not seeded
        assert sql("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='reputation' AND table_name LIKE 'dr_%';")=='0'
        header="SET time_zone='+00:00'; CREATE TABLE dr_schema_history(version INT PRIMARY KEY,checksum CHAR(64) NOT NULL,applied_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)) ENGINE=InnoDB;\n"
        ddl=header+migration.decode()+f"\nINSERT INTO dr_schema_history(version,checksum) VALUES(1,'{checksum}');\n"
        for p in EXPECTED['profiles']:
            ddl+="INSERT INTO dr_profiles(player_uuid,scope_id,community_score,system_score,visible_score,tier,profile_revision,created_at,updated_at) VALUES("+','.join(["'"+p['playerId']+"'","'"+p['scope']+"'",str(p['community']),str(p['system']),str(p['visible']),"'NEUTRAL'",str(p['revision']),"'2026-10-18 08:30:00.123456'","'2026-10-18 08:30:00.123456'"])+");\n"
        for p in EXPECTED['preferences']:ddl+=f"INSERT INTO dr_player_preferences(player_uuid,language,updated_at) VALUES('{p['playerId']}','{p['language']}','2026-10-18 08:30:00.123456');\n"
        sql(ddl);assert sql('SELECT version,checksum FROM dr_schema_history;')=='1\t'+checksum
        (directory/'phase1-seed-proof.json').write_text(json.dumps({'schema':1,'v1_sha256':checksum,'representative_controlled_data':True,'expected':EXPECTED},indent=2));seeded=True
        print('PHASE1_SEED_PASS: actual unchanged V1 DDL/checksum,3 profiles,2 preferences,both scopes',flush=True)

    def verify():
        nonlocal verified
        assert seeded and not verified
        history=sql('SELECT version,checksum FROM dr_schema_history ORDER BY version;').splitlines()
        assert [int(row.split('\t')[0]) for row in history]==list(range(1,8)),history
        assert history[0]=='1\t'+checksum
        config=directory/'plugins/DexturReputation';active=config/'storage.yml';before=active.read_bytes();assert before.startswith(b'config-version: 1\n')
        target=config/'schema1-inspection-target.yml';target.write_text((ROOT/'reputation-paper/src/main/resources/storage.yml').read_text().replace('directory: storage/json','directory: storage/schema1-verification-json'))
        environment=bindings.copy();environment['DEXTURREPUTATION_DB_PASSWORD']=bindings['MYSQL_PASSWORD'];java=str(Path(os.environ['JAVA_HOME'])/'bin/java')
        try:
            with (directory/'phase1-offline-verification.log').open('w') as out:
                subprocess.run([java,'-jar',str(artifacts/'DexturReputation-StorageAdmin.jar'),'migrate','--data-directory',str(config),'--target-config',str(target),'--backups',str(directory/'phase1-verification-backups'),'--confirm','STOPPED-ALL-WRITERS','--language','en_US'],env=environment,stdout=out,stderr=subprocess.STDOUT,check=True)
            state=json.loads((config/'storage/schema1-verification-json/state.json').read_text())['data']
            for collection,records in EXPECTED.items():
                for record in records:assert record in state[collection],(collection,record)
        finally:active.write_bytes(before)
        assert active.read_bytes()==before
        (directory/'phase1-upgrade-proof.json').write_text(json.dumps({'status':'PASS','engine':engine,'initial_schema':1,'final_schema':7,'v1_sha256':checksum,'all_seed_records_retained':True,'legacy_configuration_retained':True,'profiles':3,'preferences':2,'schema_history':history},indent=2));verified=True
        print('PHASE1_UPGRADE_PASS: native Paper V1->V7,original checksum/profiles/preferences/scopes/flat MYSQL config retained',flush=True)

    def command(*args,**kwargs):
        nonlocal container,bindings
        if args[:2]==('docker','run'):
            bindings=kwargs['env'].copy();container=args[args.index('--name')+1]
        if args[:2]==('docker','port') and '--legacy-mysql' in sys.argv:
            result=original(*args,**kwargs);seed();return result
        if args[:2]==('docker','rm') and args[-1]==container:
            try:
                if seeded:verify()
            finally:result=original(*args,**kwargs)
            return result
        return original(*args,**kwargs)

    class SubprocessProxy:
        def __getattr__(self,name):return getattr(subprocess,name)
        def run(self,args,**kwargs):
            if list(args[:2])==['docker','rm'] and args[-1]==container:
                try:
                    if seeded and not verified:verify()
                finally:result=subprocess.run(args,**kwargs)
                return result
            return subprocess.run(args,**kwargs)

    namespace['main'].__globals__['subprocess']=SubprocessProxy()
    namespace['main'].__globals__['command']=command
    namespace['main']()
    assert verified


if __name__=='__main__':main()

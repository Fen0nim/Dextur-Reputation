#!/usr/bin/env python3
"""Audit release packaging and, when supplied, compare every old public API class byte-for-byte."""
import argparse
from collections import Counter
import hashlib
import json
from pathlib import Path
import re
import zipfile

root=Path(__file__).resolve().parents[1]
p=argparse.ArgumentParser(description=__doc__)
p.add_argument('--artifacts-directory',type=Path,default=root/'reputation-paper/build/libs')
p.add_argument('--baseline-jar',type=Path)
p.add_argument('--output',type=Path)
p.add_argument('--version',default='1.0.1')
a=p.parse_args();results={}
for name in ['DexturReputation.jar','DexturReputation-StorageAdmin.jar']:
 path=a.artifacts_directory/name
 with zipfile.ZipFile(path) as z:
  names=z.namelist();duplicates=[n for n,c in Counter(names).items() if c>1];assert not duplicates,duplicates
  manifest=z.read('META-INF/MANIFEST.MF').decode();assert 'Implementation-Version: '+a.version in manifest and 'Specification-Version: 1.0' in manifest
  forbidden=('org/dextur/reputation/probe/','org/junit/','org/mockito/','org/testcontainers/','me/clip/placeholderapi/','net/luckperms/')
  assert not [n for n in names if n.startswith(forbidden)]
  test_classes=[str(t).split('/src/test/java/',1)[1][:-5] for t in root.glob('*/src/test/java/**/*.java')]
  assert not [n for n in names if n.endswith('.class') and any(n==t+'.class' or n.startswith(t+'$') for t in test_classes)]
  assert not [n for n in names if re.search(r'(^|/)(smoke-world|server.properties|eula.txt|state.json|.*\.sqlite|.*\.sql\.dump|\.env)$',n)]
  if name=='DexturReputation.jar':
   descriptor=z.read('plugin.yml').decode();assert "version: '"+a.version+"'" in descriptor
   assert "api-version: '26.2'" in descriptor and 'main: org.dextur.reputation.paper.DexturReputationPlugin' in descriptor
   assert 'depend:' not in descriptor.replace('softdepend:','')
  else:
   assert 'plugin.yml' not in names and 'paper-plugin.yml' not in names
   assert not [n for n in names if n.startswith(('org/dextur/reputation/paper/','org/bukkit/','io/papermc/'))]
   assert 'Main-Class: org.dextur.reputation.storage.StorageAdmin' in manifest
  for n in names:
   if n.endswith(('.yml','.properties','.json','.MF')):
    data=z.read(n)
    assert b'/workspace/' not in data and b'/tmp/phase' not in data,n
    assert not re.search(rb'-----BEGIN (RSA |EC |OPENSSH )?PRIVATE KEY|gh[pousr]_[A-Za-z0-9]{30,}|github_pat_[A-Za-z0-9_]{30,}',data),n
  results[name]={'sha256':hashlib.sha256(path.read_bytes()).hexdigest(),'bytes':path.stat().st_size,'entries':len(names)}
if a.baseline_jar:
 with zipfile.ZipFile(a.baseline_jar) as old,zipfile.ZipFile(a.artifacts_directory/'DexturReputation.jar') as current:
  classes=[n for n in old.namelist() if n.startswith('org/dextur/reputation/api/') and n.endswith('.class')]
  assert classes and all(old.read(n)==current.read(n) for n in classes)
  results['unchanged_public_api_classes']=len(classes)
if a.output:a.output.write_text(json.dumps(results,indent=2)+'\n')
print(json.dumps(results,indent=2))

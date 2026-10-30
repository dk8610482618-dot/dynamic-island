#!/usr/bin/env python3
"""Fail on common credential formats without printing matched credentials."""
from pathlib import Path
import re,sys
root=Path(__file__).resolve().parents[1]
checks={
 'private key':re.compile(r'-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----'),
 'AWS access key':re.compile(r'\b(?:AKIA|ASIA)[A-Z0-9]{16}\b'),
 'GitHub token':re.compile(r'\b(?:ghp_|gho_|ghu_|ghs_)[A-Za-z0-9]{36}\b'),
 'Google API key':re.compile(r'\bAIza[A-Za-z0-9_-]{35}\b'),
}
failed=[]
for path in root.rglob('*'):
 if not path.is_file() or any(p in {'.git','.gradle','build','node_modules','.kotlin'} for p in path.relative_to(root).parts):continue
 if path.suffix in {'.zip','.png','.jpg','.jar','.apk','.aab','.log'}:continue
 if path.name=='local.properties':continue
 try:text=path.read_text()
 except (UnicodeError,OSError):continue
 for number,line in enumerate(text.splitlines(),1):
  for kind,pattern in checks.items():
   if pattern.search(line):failed.append(f'{path.relative_to(root)}:{number}: possible {kind}')
print('\n'.join(failed) if failed else 'Common credential-pattern scan passed.')
sys.exit(bool(failed))

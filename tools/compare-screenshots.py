#!/usr/bin/env python3
"""Compare same-device PNG golden/actual directories. Requires Pillow; never creates goldens."""
from pathlib import Path
import argparse,sys
from PIL import Image,ImageChops
p=argparse.ArgumentParser()
p.add_argument('golden',type=Path);p.add_argument('actual',type=Path)
p.add_argument('--tolerance',type=int,default=0)
a=p.parse_args()
expected=sorted(a.golden.glob('*.png'))
if not expected:sys.exit('No golden screenshots found; record and review real device screenshots first.')
failed=[]
for golden in expected:
 actual=a.actual/golden.name
 if not actual.exists():failed.append(golden.name+': missing');continue
 with Image.open(golden) as x,Image.open(actual) as y:
  if x.size!=y.size:failed.append(golden.name+': size mismatch');continue
  diff=ImageChops.difference(x.convert('RGB'),y.convert('RGB'))
  if max(high for low,high in diff.getextrema())>a.tolerance:failed.append(golden.name+': pixels differ')
extra=set(x.name for x in a.actual.glob('*.png'))-set(x.name for x in expected)
failed.extend(name+': no reviewed golden' for name in sorted(extra))
print('\n'.join(failed) if failed else f'{len(expected)} screenshots match reviewed goldens.')
sys.exit(bool(failed))

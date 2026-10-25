#!/usr/bin/env python3
"""Package explicit source paths only; never include state, keys, APKs or databases."""
from pathlib import Path
import zipfile
ROOT=Path(__file__).resolve().parents[1]
output=ROOT/'build/hypr-watch-source.zip';output.parent.mkdir(exist_ok=True)
roots=['README.md','API.md','CONTEXT.md','LICENSE','LICENSE-AGPL','NOTICE','requirements.txt','.gitignore','android','scripts','tests','docs','plugin']
with zipfile.ZipFile(output,'w',zipfile.ZIP_DEFLATED) as archive:
 for name in roots:
  p=ROOT/name
  candidates=[p] if p.is_file() else list(p.rglob('*'))
  for file in candidates:
   rel=file.relative_to(ROOT)
   if not file.is_file() or any(x in {'__pycache__','vendor','.deps','.venv'} for x in rel.parts) or file.suffix in {'.pyc','.db','.keystore','.apk','.class'}:continue
   archive.write(file,'hypr-watch/'+str(rel))
print(output)

#!/usr/bin/env python3
"""Call the locally authenticated notes API without putting its token in arguments."""
import json
import os
from pathlib import Path
import urllib.error
import http.client
import subprocess
import urllib.request

STATE_FILES=('connection.json','companion.json')

def state_dir():
    """$XDG_STATE_HOME/hypr-watch (0700). A state file only the pre-rename redmi-notes dir holds is moved in once (0600)."""
    base=Path(os.environ.get('XDG_STATE_HOME',Path.home()/'.local/state'))
    p=base/'hypr-watch';p.mkdir(parents=True,exist_ok=True);p.chmod(0o700)
    for name in STATE_FILES:
        old=base/'redmi-notes'/name
        try:
            if not old.is_file():continue
            if (p/name).exists() and name=='companion.json':
                # Either record asking for Mi Fitness back wins, so a handoff from before the rename is not lost.
                merged=json.loads((p/name).read_text());merged['restore_mi_fitness']=bool(merged.get('restore_mi_fitness') or json.loads(old.read_text()).get('restore_mi_fitness'))
                (p/name).write_text(json.dumps(merged));old.unlink()
            elif not (p/name).exists():old.replace(p/name)
            (p/name).chmod(0o600)
        except FileNotFoundError:pass
    return p

def connection():
    state=state_dir()/'connection.json'
    if not state.exists():raise RuntimeError('Run scripts/connect-pc.py first with the phone connected.')
    return json.loads(state.read_text())

def call(path,method='GET',data=None,timeout=40):
    cfg=connection()
    # USB transports can reconnect with their previous port forwards cleared.
    try:subprocess.run(['adb','forward','tcp:8766','tcp:8766'],capture_output=True,timeout=5)
    except (OSError,subprocess.TimeoutExpired):pass
    request=urllib.request.Request(cfg['url']+'/api/'+path,data=None if data is None else json.dumps(data).encode(),method=method,headers={'Authorization':'Bearer '+cfg['token'],'Content-Type':'application/json'})
    try:
        with urllib.request.urlopen(request,timeout=timeout) as response:return json.load(response)
    except urllib.error.HTTPError as e:
        payload=json.load(e)
        raise RuntimeError(payload.get('error',f'HTTP {e.code}')) from None
    except (urllib.error.URLError,TimeoutError,ConnectionError,http.client.HTTPException):
        raise RuntimeError('Phone API unavailable. Open Hypr Watch and run scripts/connect-pc.py again.') from None

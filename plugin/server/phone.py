"""USB setup and reversible companion handoff for the locally attached phone."""
import json
import subprocess
import time
from watch_client import call,state_dir

CONNECT_WAIT_S=35
APP='local.hyprwatch'

def adb(*args,input=None):
    # No input reads from nothing: an inherited stdin would be the MCP server's own request stream.
    try:r=subprocess.run(['adb',*args],input=input,stdin=None if input is not None else subprocess.DEVNULL,capture_output=True,text=True,timeout=12,check=True)
    except (OSError,subprocess.SubprocessError):raise RuntimeError('Connect the phone by USB with debugging authorized, then retry.') from None
    return r.stdout.strip()

def connect_usb():
    adb('forward','tcp:8766','tcp:8766')
    token=adb('exec-out','run-as',APP,'cat','files/api-token')
    if len(token)<32:raise RuntimeError('Open Hypr Watch on the phone first.')
    cfg=state_dir()/'connection.json';cfg.write_text(json.dumps({'url':'http://127.0.0.1:8766','token':token}));cfg.chmod(0o600)
    return token

def watch_status():
    for attempt in range(10):
        try:return call('status',timeout=3)['watch']
        except RuntimeError:
            if attempt==9:raise
            time.sleep(.3)

def take_over():
    connect_usb()
    watch=watch_status()
    # A connect already under way (or a session restart) would be torn down by another /watch/connect.
    deadline=time.monotonic()+CONNECT_WAIT_S
    while watch['state']=='connecting':
        if time.monotonic()>=deadline:raise RuntimeError('Hypr Watch is still connecting to the watch. Check its status, then retry.')
        time.sleep(.5);watch=watch_status()
    if watch['state']=='connected':return {'watch':watch,'message':'Hypr Watch already has the watch connection.'}
    # The app owns the watch from an earlier connect, so a failure here leaves it, and Mi Fitness paused, as they are.
    owned=bool(watch.get('auto_reconnect'))
    record=state_dir()/'companion.json'
    if not record.exists():
        disabled=adb('shell','pm','list','packages','-d','com.xiaomi.wearable')
        restore='package:com.xiaomi.wearable' not in disabled
        record.write_text(json.dumps({'restore_mi_fitness':restore}));record.chmod(0o600)
    try:
        adb('shell','pm','disable-user','--user','0','com.xiaomi.wearable')
        adb('shell','am','force-stop','nodomain.freeyourgadget.gadgetbridge')
        result=call('watch/connect','POST',{})
        if result['watch']['state']!='connected':raise RuntimeError(result['watch'].get('error') or 'Could not connect to the watch')
        result['mi_fitness_paused']=True
        result['message']='Hypr Watch has the watch connection. Use disconnect_watch or connect-pc.py --release to return it to Mi Fitness.'
        return result
    except Exception as e:
        reason=(str(e) or type(e).__name__).rstrip('. ')+'.'
        if owned:raise RuntimeError(f'{reason} Hypr Watch still owns the watch and keeps reconnecting, so Mi Fitness stays off. Run connect-pc.py --release to give it back.') from e
        try:restore=record.exists() and json.loads(record.read_text()).get('restore_mi_fitness',False)
        except (OSError,ValueError,AttributeError):restore=False
        # A failed connect claims nothing, but an app that owned the watch before still does; Mi Fitness gets it back only once it is released.
        how='api'
        try:
            if restore:how=release_app(then='run connect-pc.py --release')
        except RuntimeError as r:raise RuntimeError(f'{reason} {r}') from e
        try:restore_companion()
        except Exception:raise RuntimeError(f'{reason} Mi Fitness could not be turned back on. Reconnect the phone by USB, then run connect-pc.py --release.') from e
        if how=='adb':raise RuntimeError(f'{reason} {STOPPED} Mi Fitness is back.') from e
        raise

LINK_PREFS='shared_prefs/watch-link.xml'
# LinkStore's prefs for a released watch: nothing (restore, reboot, restart) connects it again until an explicit connect.
RELEASED_PREFS="<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n<map>\n    <boolean name=\"owned\" value=\"false\" />\n    <boolean name=\"released\" value=\"true\" />\n</map>\n"
STOPPED='Hypr Watch did not answer, so it was stopped and its watch marked released over ADB. Open it again to use notes; it will not reconnect the watch.'

def release_app(then='retry'):
    """Makes Hypr Watch let go of the watch for good; returns how. Without the API, stops the app and writes the release into its prefs over ADB."""
    try:
        call('watch/disconnect','POST',{})
        return 'api'
    except Exception as e:
        reason=str(e) or type(e).__name__
    # Stopped first, so a running service cannot write ownership back; a force-stop also cancels its wakeups.
    # Written rather than deleted: no file reads as never released, and a later restore would take the watch back.
    # The backup copy goes, since Android loads it in place of the file; the file is read back to check.
    try:
        adb('shell','am','force-stop',APP)
        adb('shell',f"run-as {APP} sh -c 'mkdir -p shared_prefs && cat > {LINK_PREFS}.new && rm -f {LINK_PREFS}.bak && mv {LINK_PREFS}.new {LINK_PREFS}'",input=RELEASED_PREFS)
        if adb('exec-out','run-as',APP,'cat',LINK_PREFS)!=RELEASED_PREFS.strip():raise RuntimeError('The release did not stick.')
    except RuntimeError:
        raise RuntimeError(f'Hypr Watch did not release the watch ({reason}) and could not be stopped and marked released over ADB, so Mi Fitness stays paused. Reconnect the phone by USB, then {then}.') from None
    return 'adb'

def restore_companion():
    record=state_dir()/'companion.json'
    if not record.exists():return False
    restore=json.loads(record.read_text()).get('restore_mi_fitness',False)
    if restore:adb('shell','pm','enable','--user','0','com.xiaomi.wearable')
    record.unlink();return restore

def release():
    # Mi Fitness comes back only once Hypr Watch can no longer take the watch back.
    how=release_app()
    restored=restore_companion()
    result={'watch':{'state':'disconnected'},'mi_fitness_restored':restored}
    if how=='adb':result['message']=STOPPED
    return result

"""Exercise companion restoration failures without touching a real phone."""
from pathlib import Path
import os,re,subprocess,sys,tempfile,unittest
from xml.etree import ElementTree
from unittest.mock import patch
sys.path.insert(0,str(Path(__file__).resolve().parents[1]/'plugin/server'))
import phone,watch_client

class CompanionTest(unittest.TestCase):
 def run_failure(self,fail_command):
  calls=[]
  def fake_adb(*args,**kwargs):
   calls.append(args)
   if args==fail_command:raise RuntimeError('Simulated ADB failure')
   return ''
  def fake_call(path,*args,**kwargs):return {'watch':{'state':'disconnected'}}
  with tempfile.TemporaryDirectory() as directory,patch.object(phone,'state_dir',return_value=Path(directory)),patch.object(phone,'connect_usb',return_value='test-only'),patch.object(phone,'adb',side_effect=fake_adb),patch.object(phone,'call',side_effect=fake_call):
   with self.assertRaises(RuntimeError):phone.take_over()
   self.assertIn(('shell','pm','enable','--user','0','com.xiaomi.wearable'),calls)
   self.assertFalse((Path(directory)/'companion.json').exists())
 def test_failed_connect_releases_the_watch_before_mi_fitness_returns(self):
  calls=[]
  def fake_adb(*args,**kwargs):calls.append(('adb',)+args);return ''
  def fake_call(path,*args,**kwargs):
   calls.append(('api',path))
   if path=='status':return {'watch':{'state':'error'}}
   return {'watch':{'state':'error','error':'Timed out at opening socket'}}
  with tempfile.TemporaryDirectory() as directory,patch.object(phone,'state_dir',return_value=Path(directory)),patch.object(phone,'connect_usb',return_value='test-only'),patch.object(phone,'adb',side_effect=fake_adb),patch.object(phone,'call',side_effect=fake_call):
   with self.assertRaisesRegex(RuntimeError,'Timed out'):phone.take_over()
  enable=calls.index(('adb','shell','pm','enable','--user','0','com.xiaomi.wearable'))
  self.assertIn(('api','watch/disconnect'),calls[:enable])
 def test_force_stop_failure_restores_mi_fitness(self):self.run_failure(('shell','am','force-stop','nodomain.freeyourgadget.gadgetbridge'))
 def test_disable_failure_runs_restoration(self):self.run_failure(('shell','pm','disable-user','--user','0','com.xiaomi.wearable'))
 def test_unavailable_api_never_disables_companion(self):
  with patch.object(phone,'connect_usb',return_value='test-only'),patch.object(phone,'call',side_effect=RuntimeError('Unavailable')),patch.object(phone,'adb') as adb,patch.object(phone.time,'sleep'):
   with self.assertRaises(RuntimeError):phone.take_over()
   adb.assert_not_called()
 def take_over_after(self,states):
  """take_over() while /status reports each of [states] in turn; returns its result and the API and ADB calls."""
  states=list(states);api=[];shell=[]
  def fake_call(path,*args,**kwargs):
   api.append(path)
   if path=='status':return {'watch':{'state':states.pop(0) if len(states)>1 else states[0]}}
   return {'watch':{'state':'connected'}}
  def fake_adb(*args,**kwargs):shell.append(args);return ''
  with tempfile.TemporaryDirectory() as directory,patch.object(phone,'state_dir',return_value=Path(directory)),patch.object(phone,'connect_usb',return_value='test-only'),patch.object(phone,'adb',side_effect=fake_adb),patch.object(phone,'call',side_effect=fake_call),patch.object(phone.time,'sleep'):
   return phone.take_over(),api,shell
 def test_connect_in_progress_is_awaited_not_reopened(self):
  result,api,shell=self.take_over_after(['connecting','connecting','connected'])
  self.assertEqual(result['message'],'Hypr Watch already has the watch connection.')
  self.assertEqual(api,['status','status','status'])
  self.assertEqual(shell,[])
 def test_failed_connect_in_progress_is_retried_once(self):
  result,api,shell=self.take_over_after(['connecting','error'])
  self.assertEqual(api,['status','status','watch/connect'])
  self.assertIn(('shell','pm','disable-user','--user','0','com.xiaomi.wearable'),shell)
  self.assertTrue(result['mi_fitness_paused'])
 def test_connect_that_never_settles_is_left_alone(self):
  clock=iter(range(0,1000,10))
  with patch.object(phone.time,'monotonic',side_effect=lambda:next(clock)):
   with self.assertRaisesRegex(RuntimeError,'still connecting'):self.take_over_after(['connecting'])
 def failed_rerun(self,owned):
  """take_over() failing with a record from an earlier take-over, while the app owns the watch or not; returns the calls and whether the record is left."""
  calls=[]
  def fake_call(path,*args,**kwargs):
   calls.append(('api',path))
   if path=='status':return {'watch':{'state':'error','auto_reconnect':owned}}
   return {'watch':{'state':'error','error':'Timed out at opening socket'}}
  def fake_adb(*args,**kwargs):calls.append(('adb',)+args);return ''
  with tempfile.TemporaryDirectory() as directory,patch.object(phone,'state_dir',return_value=Path(directory)),patch.object(phone,'connect_usb',return_value='test-only'),patch.object(phone,'adb',side_effect=fake_adb),patch.object(phone,'call',side_effect=fake_call):
   record=Path(directory)/'companion.json';record.write_text('{"restore_mi_fitness": true}')
   with self.assertRaises(RuntimeError) as failure:phone.take_over()
   return calls,record.exists(),str(failure.exception)
 def test_failed_rerun_keeps_an_owning_app(self):
  """The app still owns the watch from an earlier take-over: a failure leaves it and Mi Fitness alone, and says so."""
  calls,kept,message=self.failed_rerun(True)
  self.assertTrue(kept)
  self.assertEqual(message,'Timed out at opening socket. Hypr Watch still owns the watch and keeps reconnecting, so Mi Fitness stays off. Run connect-pc.py --release to give it back.')
  self.assertNotIn(('api','watch/disconnect'),calls)
  self.assertNotIn(('adb','shell','pm','enable','--user','0','com.xiaomi.wearable'),calls)
 def test_failed_rerun_after_an_app_disconnect_gives_mi_fitness_back(self):
  """The record outlived a Disconnect in the app, which owns nothing now: a failure restores Mi Fitness as the record says."""
  calls,kept,message=self.failed_rerun(False)
  self.assertFalse(kept)
  self.assertEqual(message,'Timed out at opening socket')
  enable=calls.index(('adb','shell','pm','enable','--user','0','com.xiaomi.wearable'))
  self.assertIn(('api','watch/disconnect'),calls[:enable])
 def test_failed_take_over_names_the_adb_fallback(self):
  def fake_call(path,*args,**kwargs):
   if path=='status':return {'watch':{'state':'error'}}
   if path=='watch/disconnect':raise RuntimeError('Hypr Watch is not answering')
   return {'watch':{'state':'error','error':'Timed out at opening socket'}}
  def fake_adb(*args,**kwargs):return phone.RELEASED_PREFS.strip() if args==ReleaseTest.READ[1:] else ''
  with tempfile.TemporaryDirectory() as directory,patch.object(phone,'state_dir',return_value=Path(directory)),patch.object(phone,'connect_usb',return_value='test-only'),patch.object(phone,'adb',side_effect=fake_adb),patch.object(phone,'call',side_effect=fake_call):
   with self.assertRaises(RuntimeError) as caught:phone.take_over()
  self.assertEqual(str(caught.exception),'Timed out at opening socket. '+phone.STOPPED+' Mi Fitness is back.')
class ReleaseTest(unittest.TestCase):
 """Mi Fitness comes back only once Hypr Watch can no longer take the watch back."""
 ENABLE=('adb','shell','pm','enable','--user','0','com.xiaomi.wearable')
 STOP=('adb','shell','am','force-stop','local.hyprwatch')
 WRITE=('adb','shell',"run-as local.hyprwatch sh -c 'mkdir -p shared_prefs && cat > shared_prefs/watch-link.xml.new && rm -f shared_prefs/watch-link.xml.bak && mv shared_prefs/watch-link.xml.new shared_prefs/watch-link.xml'")
 READ=('adb','exec-out','run-as','local.hyprwatch','cat','shared_prefs/watch-link.xml')
 def release(self,api_error=None,adb_fails=(),read_back=None):
  """release() with the API failing as [api_error], the ADB commands in [adb_fails] failing and the prefs reading back as [read_back]; returns its result or error, the calls, what was written and whether the restore record is left."""
  calls=[];written=[]
  def fake_call(path,*args,**kwargs):
   calls.append(('api',path))
   if api_error:raise api_error
   return {'watch':{'state':'disconnected'}}
  def fake_adb(*args,input=None):
   calls.append(('adb',)+args)
   if ('adb',)+args in adb_fails:raise RuntimeError('Connect the phone by USB with debugging authorized, then retry.')
   if input is not None:written.append(input)
   if ('adb',)+args==self.READ:return (written[-1] if read_back is None else read_back).strip()
   return ''
  with tempfile.TemporaryDirectory() as directory,patch.object(phone,'state_dir',return_value=Path(directory)),patch.object(phone,'adb',side_effect=fake_adb),patch.object(phone,'call',side_effect=fake_call):
   record=Path(directory)/'companion.json';record.write_text('{"restore_mi_fitness": true}')
   try:result=phone.release()
   except RuntimeError as e:result=e
   return result,calls,written,record.exists()
 def test_api_release_restores_mi_fitness(self):
  result,calls,written,left=self.release()
  self.assertEqual(calls,[('api','watch/disconnect'),self.ENABLE])
  self.assertTrue(result['mi_fitness_restored']);self.assertFalse(left)
 def test_failed_api_writes_the_release_over_adb_first(self):
  result,calls,written,left=self.release(RuntimeError('Hypr Watch is not answering'))
  self.assertEqual(calls,[('api','watch/disconnect'),self.STOP,self.WRITE,self.READ,self.ENABLE])
  self.assertTrue(result['mi_fitness_restored']);self.assertIn('over ADB',result['message']);self.assertFalse(left)
 def test_adb_release_is_remembered(self):
  """The prefs say released, as LinkStore reads them, so a later restore leaves the watch with Mi Fitness; deleting them would read as never released."""
  result,calls,written,left=self.release(RuntimeError('Hypr Watch is not answering'))
  self.assertFalse(any('rm' in c and 'shared_prefs/watch-link.xml' in c for c in calls))
  prefs=ElementTree.fromstring(written[0])
  self.assertEqual(prefs.tag,'map')
  booleans={e.get('name'):e.get('value') for e in prefs if e.tag=='boolean'}
  self.assertEqual(booleans,{'owned':'false','released':'true'})
  self.assertEqual(len(prefs),2)
  source=(Path(__file__).resolve().parents[1]/'android/src/local/hyprwatch/NotesService.kt').read_text()
  store=source[source.index('class LinkStore'):source.index('class LinkStartReceiver')]
  self.assertIn('private const val FILE = "watch-link"',store)
  self.assertEqual(set(re.findall(r'getBoolean\("(\w+)"',store)),set(booleans))
  self.assertEqual(set(re.findall(r'putBoolean\("(\w+)"',store)),set(booleans))
 def test_malformed_reply_counts_as_a_failed_release(self):
  result,calls,written,left=self.release(KeyError('watch'))
  self.assertEqual(calls,[('api','watch/disconnect'),self.STOP,self.WRITE,self.READ,self.ENABLE])
 def test_mi_fitness_stays_paused_while_the_app_may_reconnect(self):
  for fails in ((self.STOP,),(self.WRITE,),(self.READ,)):
   result,calls,written,left=self.release(RuntimeError('Hypr Watch is not answering'),fails)
   self.assertIsInstance(result,RuntimeError);self.assertIn('Mi Fitness stays paused',str(result))
   self.assertNotIn(self.ENABLE,calls);self.assertTrue(left)
  result,calls,written,left=self.release(RuntimeError('Hypr Watch is not answering'),read_back='<map />')
  self.assertIsInstance(result,RuntimeError);self.assertNotIn(self.ENABLE,calls);self.assertTrue(left)
 def test_failed_take_over_keeps_mi_fitness_paused_when_release_fails(self):
  calls=[]
  def fake_call(path,*args,**kwargs):
   calls.append(('api',path))
   if path=='status':return {'watch':{'state':'error'}}
   if path=='watch/disconnect':raise RuntimeError('Hypr Watch is not answering')
   return {'watch':{'state':'error','error':'Timed out at opening socket'}}
  def fake_adb(*args,**kwargs):
   calls.append(('adb',)+args)
   if ('adb',)+args==self.STOP:raise RuntimeError('Connect the phone by USB with debugging authorized, then retry.')
   return ''
  with tempfile.TemporaryDirectory() as directory,patch.object(phone,'state_dir',return_value=Path(directory)),patch.object(phone,'connect_usb',return_value='test-only'),patch.object(phone,'adb',side_effect=fake_adb),patch.object(phone,'call',side_effect=fake_call):
   with self.assertRaises(RuntimeError) as caught:phone.take_over()
   self.assertTrue((Path(directory)/'companion.json').exists())
  self.assertRegex(str(caught.exception),r'^Timed out at opening socket\. Hypr Watch did not release the watch .*Mi Fitness stays paused\. Reconnect the phone by USB, then run connect-pc\.py --release\.$')
  self.assertNotIn(self.ENABLE,calls)
class StateDirTest(unittest.TestCase):
 """State from before the rename (redmi-notes) is moved into hypr-watch once, so --release still restores Mi Fitness."""
 def test_old_state_files_move_once(self):
  with tempfile.TemporaryDirectory() as directory,patch.dict(os.environ,{'XDG_STATE_HOME':directory}):
   old=Path(directory)/'redmi-notes';old.mkdir(mode=0o700)
   for name,text in (('companion.json','{"restore_mi_fitness": true}'),('connection.json','{"token": "old"}'),('signing.keystore','test-only')):
    (old/name).write_text(text);(old/name).chmod(0o644)
   new=Path(directory)/'hypr-watch';new.mkdir(mode=0o755);(new/'connection.json').write_text('{"token": "new"}')
   d=phone.state_dir()
   self.assertEqual(d,new)
   self.assertEqual((new/'companion.json').read_text(),'{"restore_mi_fitness": true}')
   self.assertEqual(oct((new/'companion.json').stat().st_mode&0o777),'0o600')
   self.assertEqual(oct(new.stat().st_mode&0o777),'0o700')
   self.assertFalse((old/'companion.json').exists())
   self.assertEqual((new/'connection.json').read_text(),'{"token": "new"}')
   self.assertEqual((old/'connection.json').read_text(),'{"token": "old"}')
   self.assertTrue((old/'signing.keystore').exists())
   self.assertFalse((new/'signing.keystore').exists())
   (new/'companion.json').unlink()
   phone.state_dir()
   self.assertFalse((new/'companion.json').exists())
   self.assertEqual(phone.restore_companion(),False)
 def test_connection_reads_the_old_dir(self):
  with tempfile.TemporaryDirectory() as directory,patch.dict(os.environ,{'XDG_STATE_HOME':directory}):
   old=Path(directory)/'redmi-notes';old.mkdir();(old/'connection.json').write_text('{"url": "http://127.0.0.1:8766", "token": "t"}')
   self.assertEqual(watch_client.connection()['token'],'t')
   self.assertTrue((Path(directory)/'hypr-watch/connection.json').exists())
 def test_old_restore_record_wins_over_a_new_one(self):
  with tempfile.TemporaryDirectory() as directory,patch.dict(os.environ,{'XDG_STATE_HOME':directory}):
   old=Path(directory)/'redmi-notes';old.mkdir();(old/'companion.json').write_text('{"restore_mi_fitness": true}')
   new=Path(directory)/'hypr-watch';new.mkdir();(new/'companion.json').write_text('{"restore_mi_fitness": false}')
   phone.state_dir()
   self.assertFalse((old/'companion.json').exists())
   with patch.object(phone,"adb") as adb:self.assertEqual(phone.restore_companion(),True)
   adb.assert_called_once_with("shell","pm","enable","--user","0","com.xiaomi.wearable")
class BuildKeyTest(unittest.TestCase):
 """scripts/build-android.sh never makes a signing key unless asked; it stops before building."""
 script=Path(__file__).resolve().parents[1]/'scripts/build-android.sh'
 def build(self,state,*args):
  return subprocess.run(['bash',str(self.script),*args],env={**os.environ,'XDG_STATE_HOME':str(state)},capture_output=True,text=True,timeout=30)
 def test_missing_key_stops_the_build(self):
  with tempfile.TemporaryDirectory() as directory:
   r=self.build(directory)
   self.assertEqual(r.returncode,1)
   self.assertIn(f'No signing key at {directory}/hypr-watch/signing.keystore.',r.stderr)
   self.assertIn('run again with --new-key',r.stderr)
   self.assertNotIn('redmi-notes',r.stderr)
   self.assertEqual(r.stdout,'')
   self.assertEqual(list(Path(directory).iterdir()),[])
 def test_old_key_is_named_not_copied(self):
  with tempfile.TemporaryDirectory() as directory:
   old=Path(directory)/'redmi-notes';old.mkdir();(old/'signing.keystore').write_bytes(b'test-only')
   r=self.build(directory)
   self.assertEqual(r.returncode,1)
   self.assertIn(f'The key from before the rename is at {old}/signing.keystore.',r.stderr)
   self.assertIn(f'mv {old}/signing.keystore {directory}/hypr-watch/signing.keystore',r.stderr)
   self.assertFalse((Path(directory)/'hypr-watch').exists())
   self.assertEqual((old/'signing.keystore').read_bytes(),b'test-only')
 def test_unknown_argument(self):
  with tempfile.TemporaryDirectory() as directory:
   r=self.build(directory,'--new')
   self.assertEqual(r.returncode,2)
   self.assertIn('[--new-key]',r.stderr)
if __name__=='__main__':unittest.main()

"""Exercise watch feature MCP tools without contacting a phone."""
import base64
from pathlib import Path
import sys,tempfile,unittest
from unittest.mock import patch

sys.path.insert(0,str(Path(__file__).resolve().parents[1]/'plugin/server'))
try:
 import main
except ModuleNotFoundError as error:
 if error.name=='mcp' or error.name.startswith('mcp.'):
  raise SystemExit('mcp is missing from .deps: python -m pip install --target .deps -r plugin/requirements.txt') from error
 raise

class FeatureToolsTest(unittest.TestCase):
 def assert_api_call(self,mock_call,path,method='GET',data=None):
  self.assertEqual(mock_call.call_count,1)
  args,kwargs=mock_call.call_args
  actual_path=args[0] if args else kwargs['path']
  actual_method=args[1] if len(args)>1 else kwargs.get('method','GET')
  actual_data=args[2] if len(args)>2 else kwargs.get('data')
  self.assertEqual((actual_path,actual_method,actual_data),(path,method,data))

 def assert_tool(self,tool,arguments,path,method='GET',data=None):
  result={'ok':True}
  with patch.object(main,'call',return_value=result) as api:
   self.assertIs(tool(*arguments),result)
  self.assert_api_call(api,path,method,data)

 def assert_invalid(self,tool,arguments,message):
  with patch.object(main,'call') as api:
   with self.assertRaisesRegex(ValueError,message):tool(*arguments)
   api.assert_not_called()

 def test_get_and_action_tools(self):
  cases=(
   (main.device_status,(),'device','GET',None),
   (main.find_phone_stop,(),'device/find-phone/stop','POST',{}),
   (main.sync_watch_time,(),'device/time-sync','POST',{}),
   (main.weather_get,(),'weather','GET',None),
   (main.weather_refresh,(),'weather/refresh','POST',{}),
   (main.music_status,(),'music','GET',None),
   (main.list_alarms,(),'alarms','GET',None),
   (main.list_alarms,(True,),'alarms/refresh','POST',{}),
   (main.get_sleep_schedule,(),'alarms/sleep-mode','GET',None),
   (main.get_watch_settings,(),'watch-settings','GET',None),
   (main.get_live_heart_rate,(),'heart-rate/live','GET',None),
   (main.start_live_heart_rate,(),'heart-rate/live','POST',{'on':True}),
   (main.list_watch_faces,(),'watch-faces','GET',None),
   (main.list_watch_faces,(True,),'watch-faces?refresh=true','GET',None),
   (main.get_favourite_contacts,(),'contacts','GET',None),
   (main.get_quick_replies,(),'quick-replies','GET',None),
  )
  for tool,arguments,path,method,data in cases:
   with self.subTest(tool=tool.__name__,arguments=arguments):self.assert_tool(tool,arguments,path,method,data)

 def test_device_and_music_writes(self):
  cases=(
   (main.find_watch,(True,),'device/find-watch',{'on':True}),
   (main.music_set_enabled,(False,),'music/settings',{'enabled':False}),
  )
  for tool,arguments,path,data in cases:
   with self.subTest(tool=tool.__name__):self.assert_tool(tool,arguments,path,'POST' if tool is main.find_watch else 'PUT',data)

 def test_watch_operation_conflict_surfaces(self):
  with patch.object(main,'call',side_effect=RuntimeError('Another watch operation is in progress')) as api:
   with self.assertRaisesRegex(RuntimeError,'^Another watch operation is in progress$'):main.sync_watch_time()
  self.assert_api_call(api,'device/time-sync','POST',{})

 def test_weather_set(self):
  data={'units':'imperial','enabled':True,'location':{'mode':'manual','name':'Oslo','lat':59.91,'lon':10.75}}
  self.assert_tool(main.weather_set,('imperial',True,'manual',59.91,10.75,'Oslo'),'weather/settings','PUT',data)
  self.assert_tool(main.weather_set,(None,None,'device',None,None,'Home'),'weather/settings','PUT',{'location':{'mode':'device','name':'Home'}})
  self.assert_tool(main.weather_set,(),'weather/settings','PUT',{})

 def test_alarm_writes(self):
  self.assert_tool(
   main.create_alarm,
   ('07:30',['mon','wed'],'Work',True,False),
   'alarms','POST',
   {'time':'07:30','smart':True,'enabled':False,'repeat':['mon','wed'],'label':'Work'},
  )
  self.assert_tool(
   main.update_alarm,
   ('42','08:15',['tue'],'Later',False,True),
   'alarms/42','PUT',
   {'time':'08:15','repeat':['tue'],'label':'Later','smart':False,'enabled':True},
  )
  self.assert_tool(main.delete_alarm,('42',),'alarms/42','DELETE',{})
  seen={'time':'07:30','repeat':['mon'],'repeat_mode':'weekly','enabled':True,'smart':False}
  self.assert_tool(main.update_alarm,('42',None,None,None,None,False,seen),'alarms/42','PUT',{'enabled':False,'expect':seen})
  self.assert_tool(main.delete_alarm,('42',seen),'alarms/42','DELETE',{'expect':seen})
  self.assert_tool(main.set_sleep_schedule,(True,'22:30','06:45'),'alarms/sleep-mode','PUT',{'enabled':True,'start':'22:30','end':'06:45'})

 def test_watch_settings_write_passes_keys_through(self):
  settings={'hr_interval':'10min','hr_high_alert':120,'sedentary_start':'09:00','stress_auto':True,'secondary_goal':'moving'}
  self.assert_tool(main.set_watch_settings,(settings,),'watch-settings','PUT',settings)

 def test_watch_face_routes(self):
  self.assert_tool(main.set_watch_face,('12345',),'watch-faces/12345/activate','POST',{})
  self.assert_tool(main.delete_watch_face,('12345',),'watch-faces/12345','DELETE',{})

 def test_install_watch_face(self):
  content=b'\x5a\xa5watch face'
  with tempfile.TemporaryDirectory() as directory:
   path=Path(directory)/'custom.bin'
   path.write_bytes(content)
   self.assert_tool(
    main.install_watch_face,(str(path),),'watch-faces/upload','POST',
    {'data':base64.b64encode(content).decode('ascii')},
   )

 def test_install_watch_face_path_forms(self):
  content=b'\x5a\xa5face'
  with tempfile.TemporaryDirectory() as directory:
   path=Path(directory)/'Custom.BIN'
   path.write_bytes(content)
   link=Path(directory)/'link.bin'
   link.symlink_to(path)
   expected={'data':base64.b64encode(content).decode('ascii')}
   self.assert_tool(main.install_watch_face,(str(path),),'watch-faces/upload','POST',expected)
   self.assert_tool(main.install_watch_face,(str(link),),'watch-faces/upload','POST',expected)
   with patch.dict('os.environ',{'HOME':directory}):
    self.assert_tool(main.install_watch_face,('~/Custom.BIN',),'watch-faces/upload','POST',expected)
   other=Path(directory)/'face.txt'
   other.write_bytes(content)
   (Path(directory)/'disguised.bin').symlink_to(other)
   self.assert_invalid(main.install_watch_face,(str(Path(directory)/'disguised.bin'),),'must end in \\.bin')

 def read_sizes(self,path):
  """Runs install_watch_face on path and returns the size of every read it made of the file."""
  sizes=[]
  real_open=Path.open
  def spy_open(self_path,*args,**kwargs):
   handle=real_open(self_path,*args,**kwargs)
   real_read=handle.read
   def read(size=-1):
    sizes.append(size)
    return real_read(size)
   handle.read=read
   return handle
  with patch.object(Path,'open',spy_open),patch.object(main,'call') as api:
   with self.assertRaises(ValueError) as caught:main.install_watch_face(str(path))
   api.assert_not_called()
  return sizes,str(caught.exception)

 def test_install_watch_face_reads_are_bounded(self):
  with tempfile.TemporaryDirectory() as directory:
   huge=Path(directory)/'huge.bin'
   with huge.open('wb') as f:
    f.write(b'\x5a\xa5')
    f.truncate(50_000_000)
   sizes,message=self.read_sizes(huge)
   self.assertEqual((sizes,message),([2,5_999_999],'Watch face files are limited to 6000000 bytes.'))
   bad=Path(directory)/'bad.bin'
   bad.write_bytes(b'PK'+b'0'*1_000_000)
   sizes,message=self.read_sizes(bad)
   self.assertEqual((sizes,message),([2],'Not a watch face file: it does not start with 5A A5.'))

 def test_contacts_and_quick_replies_writes(self):
  contacts=[{'name':'Ada','number':'+44 7700 900123'},{'name':'Lin','number':'555 0100'}]
  replies=['On my way','Call you soon']
  self.assert_tool(main.set_favourite_contacts,(contacts,),'contacts','PUT',{'contacts':contacts})
  self.assert_tool(main.set_quick_replies,(replies,),'quick-replies','PUT',{'replies':replies})

 def test_invalid_device_weather_and_music_inputs_do_not_call_api(self):
  cases=(
   (main.find_watch,(1,),'on must be true or false'),
   (main.music_set_enabled,('yes',),'enabled must be true or false'),
   (main.list_alarms,('yes',),'refresh must be true or false'),
   (main.list_watch_faces,(1,),'refresh must be true or false'),
   (main.weather_set,('kelvin',None,None,None,None,None),'units must be metric or imperial'),
   (main.weather_set,([],None,None,None,None,None),'units must be metric or imperial'),
   (main.weather_set,(None,None,None,1.0,None,None),'require location_mode'),
   (main.weather_set,(None,None,'gps',None,None,None),'location_mode must be device or manual'),
   (main.weather_set,(None,None,'device',1.0,None,None),'device location takes no coordinates'),
   (main.weather_set,(None,None,'manual',None,2.0,'Place'),'manual location requires'),
   (main.weather_set,(None,None,'manual',91.0,2.0,'Place'),'lat must be a number between'),
   (main.weather_set,(None,None,'manual',1.0,2.0,''),'name must be 1 to 32'),
  )
  for tool,arguments,message in cases:
   with self.subTest(tool=tool.__name__,arguments=arguments):self.assert_invalid(tool,arguments,message)

 def test_invalid_alarm_inputs_do_not_call_api(self):
  cases=(
   (main.create_alarm,('7:30',),'time must be a time'),
   (main.create_alarm,('07:30',['monday']),'repeat days must be one of'),
   (main.create_alarm,('07:30',None,'x'*41),'label must be at most 40'),
   (main.update_alarm,('01',),'alarm_id must be 0 or 1 to 10'),
   (main.update_alarm,('0',),'at least one alarm field'),
   (main.update_alarm,('4',),'at least one alarm field'),
   (main.delete_alarm,('../4',),'alarm_id must be 0 or 1 to 10'),
   (main.delete_alarm,('4','07:30'),'expect must be an object'),
   (main.delete_alarm,('4',{'label':'Gym'}),'Unknown expect field label'),
   (main.update_alarm,('4',None,None,None,None,False,{'time':'7:30'}),'expect.time must be a time'),
   (main.update_alarm,('4',None,None,None,None,False,{'repeat_mode':5}),'expect.repeat_mode must be text'),
   (main.update_alarm,('4',None,None,None,None,None,{'enabled':True}),'at least one alarm field'),
   (main.set_sleep_schedule,(),'Give enabled, start, or end'),
   (main.set_sleep_schedule,(True,'22:00','22:00'),'start and end must differ'),
  )
  for tool,arguments,message in cases:
   with self.subTest(tool=tool.__name__,arguments=arguments):self.assert_invalid(tool,arguments,message)

 def test_invalid_watch_settings_do_not_call_api(self):
  cases=(
   ({},'at least one setting'),
   ({'mystery':True},'Unknown watch setting'),
   ({'stress_auto':'yes'},'stress_auto must be true or false'),
   ({'sedentary_start':'9:00'},'sedentary_start must be a time'),
   ({'hr_interval':'5min'},'hr_interval must be one of'),
   ({'hr_interval':[]},'hr_interval must be one of'),
   ({'hr_high_alert':125},'hr_high_alert must be one of'),
  )
  for settings,message in cases:
   with self.subTest(settings=settings):self.assert_invalid(main.set_watch_settings,(settings,),message)

 def test_invalid_watch_face_files_do_not_call_api(self):
  with tempfile.TemporaryDirectory() as directory:
   root=Path(directory)
   wrong_extension=root/'face.txt'
   wrong_extension.write_bytes(b'\x5a\xa5')
   bad_magic=root/'bad.bin'
   bad_magic.write_bytes(b'no')
   too_large=root/'large.bin'
   too_large.write_bytes(b'\x5a\xa5'+b'0'*5_999_999)
   cases=(
    (str(root/'missing.bin'),'does not exist'),
    (str(wrong_extension),'must end in \\.bin'),
    (str(bad_magic),'does not start with 5A A5'),
    (str(too_large),'limited to 6000000 bytes'),
   )
   for path,message in cases:
    with self.subTest(path=path):self.assert_invalid(main.install_watch_face,(path,),message)

 def test_invalid_face_ids_contacts_and_replies_do_not_call_api(self):
  cases=(
   (main.set_watch_face,('../1',),'face_id must be 1 to 20'),
   (main.delete_watch_face,('1/2',),'face_id must be 1 to 20'),
   (main.set_favourite_contacts,([{'name':'','number':'123'}],),'Contact 1: use a name'),
   (main.set_favourite_contacts,([{'name':'A','number':'123'},{'name':'B','number':'1 2 3'}],),'already in the list'),
   (main.set_favourite_contacts,([{'name':'A','number':'abc'}],),'use a number of digits'),
   (main.set_quick_replies,([''],),'Reply 1: use 1 to 30'),
   (main.set_quick_replies,(['x']*17,),'at most 16 quick replies'),
  )
  for tool,arguments,message in cases:
   with self.subTest(tool=tool.__name__,arguments=arguments):self.assert_invalid(tool,arguments,message)

if __name__=='__main__':unittest.main()

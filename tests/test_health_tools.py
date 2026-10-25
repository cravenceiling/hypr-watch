"""Exercise health and notification MCP tools without contacting a phone."""
from pathlib import Path
import sys,unittest
from unittest.mock import call,patch

sys.path.insert(0,str(Path(__file__).resolve().parents[1]/'plugin/server'))
try:
 import main
except ModuleNotFoundError as error:
 if error.name=='mcp' or error.name.startswith('mcp.'):
  raise SystemExit('mcp is missing from .deps: python -m pip install --target .deps -r plugin/requirements.txt') from error
 raise

class HealthToolsTest(unittest.TestCase):
 def assert_api_call(self,mock_call,path,method='GET',data=None):
  self.assertEqual(mock_call.call_count,1)
  args,kwargs=mock_call.call_args
  actual_path=args[0] if args else kwargs['path']
  actual_method=args[1] if len(args)>1 else kwargs.get('method','GET')
  actual_data=args[2] if len(args)>2 else kwargs.get('data')
  self.assertEqual((actual_path,actual_method,actual_data),(path,method,data))

 def test_health_status_combines_status_and_profile(self):
  status={'state':'idle','fetched':4}
  with patch.object(main,'call',side_effect=[{'health':status},{'configured':True}]) as api:
   self.assertEqual(main.health_status(),{'state':'idle','fetched':4,'configured':True})
  self.assertEqual(api.call_args_list,[call('status'),call('health/profile')])
  self.assertEqual(status,{'state':'idle','fetched':4})

 def test_sync_health(self):
  result={'started':True,'health':{'state':'listing','fetched':0}}
  with patch.object(main,'call',return_value=result) as api:
   self.assertEqual(main.sync_health(),result)
  self.assert_api_call(api,'health/sync','POST',{})

 def test_sync_health_without_watch(self):
  with patch.object(main,'call',side_effect=RuntimeError('Connect the watch first')) as api:
   with self.assertRaisesRegex(RuntimeError,'^Connect the watch first$'):main.sync_health()
  self.assert_api_call(api,'health/sync','POST',{})

 def test_health_days(self):
  with patch.object(main,'call',return_value={'days':[]}) as api:
   self.assertEqual(main.health_days('2026-01-02','2026-01-31'),{'days':[]})
  self.assert_api_call(api,'health/days?from=2026-01-02&to=2026-01-31')

 def test_health_series(self):
  with patch.object(main,'call',return_value={'points':[]}) as api:
   self.assertEqual(main.health_series('heart_rate','2026-02-01','2026-02-03',5),{'points':[]})
  self.assert_api_call(api,'health/series?metric=heart_rate&from=2026-02-01&to=2026-02-03&bucket=5')

 def test_health_range_tools(self):
  tools=(
   (main.health_sleep,'health/sleep?from=2026-03-01&to=2026-03-02'),
   (main.health_samples,'health/samples?from=2026-03-01&to=2026-03-02'),
   (main.health_workouts,'health/workouts?from=2026-03-01&to=2026-03-02'),
  )
  for tool,path in tools:
   with self.subTest(tool=tool.__name__),patch.object(main,'call',return_value={}) as api:
    self.assertEqual(tool('2026-03-01','2026-03-02'),{})
    self.assert_api_call(api,path)

 def test_get_health_profile(self):
  with patch.object(main,'call',return_value={'configured':False,'profile':None}) as api:
   self.assertEqual(main.get_health_profile(),{'configured':False,'profile':None})
  self.assert_api_call(api,'health/profile')

 def test_set_health_profile(self):
  data={'height_cm':178,'weight_kg':72.5,'birthday':'1990-04-05','gender':'male','goal_calories':600,'goal_steps':10000,'goal_standing_hours':10,'goal_moving_minutes':45}
  with patch.object(main,'call',return_value={'configured':True}) as api:
   self.assertEqual(main.set_health_profile(**data),{'configured':True})
  self.assert_api_call(api,'health/profile','PUT',data)

 def test_get_notification_settings(self):
  result={'calls':True,'allowed':['com.example.app']}
  with patch.object(main,'call',return_value=result) as api:
   self.assertEqual(main.get_notification_settings(),result)
  self.assert_api_call(api,'notifications')

 def test_set_notification_settings(self):
  data={'allowed':['com.example.chat','com.example.mail'],'calls':False}
  with patch.object(main,'call',return_value=data) as api:
   self.assertEqual(main.set_notification_settings(**data),data)
  self.assert_api_call(api,'notifications','PUT',data)

 def test_invalid_series_arguments_do_not_call_api(self):
  cases=(
   (('temperature','2026-01-01','2026-01-02',15),'metric must be one of'),
   (('steps','2026-01-01','2026-01-02',30),'bucket_minutes must be one of'),
  )
  for arguments,message in cases:
   with self.subTest(arguments=arguments),patch.object(main,'call') as api:
    with self.assertRaisesRegex(ValueError,message):main.health_series(*arguments)
    api.assert_not_called()

 def test_invalid_date_ranges_do_not_call_api(self):
  cases=(
   (('20260101','2026-01-02'),'start_date must be a valid date'),
   (('2026-01-01','not-a-date'),'end_date must be a valid date'),
   (('2026-01-03','2026-01-02'),'start_date must be on or before end_date'),
   (('2024-01-01','2025-02-04'),'Date range must not exceed 400 days'),
  )
  for arguments,message in cases:
   with self.subTest(arguments=arguments),patch.object(main,'call') as api:
    with self.assertRaisesRegex(ValueError,message):main.health_days(*arguments)
    api.assert_not_called()

 def test_invalid_birthday_does_not_call_api(self):
  with patch.object(main,'call') as api:
   with self.assertRaisesRegex(ValueError,'birthday must be a valid date'):
    main.set_health_profile(178,72.5,'19900405','male',600,10000,10,45)
   api.assert_not_called()

if __name__=='__main__':unittest.main()

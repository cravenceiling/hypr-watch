"""Exercise the health summary, trend and sleep analysis MCP tools against a mocked phone API."""
import datetime
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

TZ=-5*3600
DAY_FIELDS=(
 'steps','active_calories','calories','distance_m','resting_hr','avg_hr','min_hr','max_hr','avg_spo2','min_spo2',
 'avg_stress','max_stress','standing_hours','sleep_minutes','deep_minutes','light_minutes','rem_minutes','awake_minutes',
)

def day(date,**fields):
 return {'date':date}|{f:None for f in DAY_FIELDS}|fields

def at(local):
 return int(datetime.datetime.fromisoformat(local).replace(tzinfo=datetime.timezone(datetime.timedelta(seconds=TZ))).timestamp())

def night(bed,wake,total,stages=(None,None,None,None),finished=True):
 deep,light,rem,awake=stages
 return {'bed_time':at(bed),'wakeup_time':at(wake),'tz_offset':TZ,'finished':finished,'total_minutes':total,
  'deep_minutes':deep,'light_minutes':light,'rem_minutes':rem,'awake_minutes':awake,'quality':None,'stages':[]}

def workout(sport,start,end,active_seconds):
 return {'start':at(start),'end':at(end),'tz_offset':TZ,'sport':sport,'active_seconds':active_seconds,'calories':None,
  'avg_hr':None,'max_hr':None,'min_hr':None,'steps':None,'distance_m':None}

def phone(responses):
 """A call() stand-in that answers each GET path from responses and fails on anything else."""
 def answer(path,method='GET',data=None):
  if method!='GET' or path not in responses:raise AssertionError(f'Unexpected call {method} {path}')
  return responses[path]
 return answer

class HealthInsightsTest(unittest.TestCase):
 def setUp(self):
  today=patch.object(main,'_today',return_value=datetime.date(2026,10,24))
  today.start()
  self.addCleanup(today.stop)

 def test_period_summary_compares_with_previous_period(self):
  current='from=2026-10-12&to=2026-10-18'
  previous='from=2026-10-05&to=2026-10-11'
  responses={
   'health/days?'+current:{'days':[
    day('2026-10-12',steps=8000,active_calories=300,distance_m=6000,standing_hours=10,avg_hr=70,min_hr=52,max_hr=140,avg_spo2=97,avg_stress=30),
    day('2026-10-13',steps=10000,active_calories=400,distance_m=7500,standing_hours=11,avg_hr=72,min_hr=50,max_hr=150,avg_spo2=96,avg_stress=40),
   ]},
   'health/sleep?'+current:{'sessions':[
    night('2026-10-12T23:30','2026-10-13T07:00',420),
    night('2026-10-14T00:30','2026-10-14T07:30',400),
    night('2026-10-14T14:00','2026-10-14T15:00',60),
    night('2026-10-17T23:00','2026-10-18T03:00',240,finished=False),
   ]},
   'health/workouts?'+current:{'workouts':[
    workout('outdoor_walking','2026-10-12T18:00','2026-10-12T18:40',1800),
    workout('outdoor_walking','2026-10-13T18:00','2026-10-13T18:20',None),
    workout('jump_rope','2026-10-13T19:00','2026-10-13T19:15',600),
   ]},
   'health/days?'+previous:{'days':[
    day('2026-10-06',steps=6000,active_calories=250,distance_m=4000,standing_hours=9,resting_hr=58,avg_hr=68,min_hr=51,max_hr=130,avg_spo2=97,avg_stress=25),
   ]},
   'health/sleep?'+previous:{'sessions':[night('2026-10-06T23:00','2026-10-07T06:30',390)]},
   'health/workouts?'+previous:{'workouts':[]},
  }
  with patch.object(main,'call',side_effect=phone(responses)) as api:
   result=main.health_period_summary('2026-10-12','2026-10-18')
  self.assertEqual(api.call_args_list,[
   call('health/sleep?'+current),call('health/days?'+current),call('health/workouts?'+current),
   call('health/sleep?'+previous),call('health/days?'+previous),call('health/workouts?'+previous),
  ])
  self.assertEqual(result,{
   'period':{'start':'2026-10-12','end':'2026-10-18','days':7},
   'previous_period':{'start':'2026-10-05','end':'2026-10-11','days':7},
   'current':{
    'days_with_data':2,'steps_total':18000,'steps_per_day_avg':9000,'active_calories_kcal_per_day_avg':350,
    'distance_km_total':13.5,'standing_hours_per_day_avg':10.5,'resting_hr_bpm_avg':None,'avg_hr_bpm':71.0,
    'min_hr_bpm':50,'max_hr_bpm':150,'stress_score_avg':35.0,'spo2_pct_avg':96.5,'sleep_nights':2,
    'sleep_minutes_per_night_avg':410,'bedtime_local_avg':'00:00','bedtime_std_dev_minutes':30,'wake_time_local_avg':'07:15','workouts':3,
    'workout_active_minutes_total':60,
    'workouts_by_sport':{'outdoor_walking':{'count':2,'active_minutes':50},'jump_rope':{'count':1,'active_minutes':10}},
   },
   'previous':{
    'days_with_data':1,'steps_total':6000,'steps_per_day_avg':6000,'active_calories_kcal_per_day_avg':250,
    'distance_km_total':4.0,'standing_hours_per_day_avg':9.0,'resting_hr_bpm_avg':58.0,'avg_hr_bpm':68.0,
    'min_hr_bpm':51,'max_hr_bpm':130,'stress_score_avg':25.0,'spo2_pct_avg':97.0,'sleep_nights':1,
    'sleep_minutes_per_night_avg':390,'bedtime_local_avg':'23:00','bedtime_std_dev_minutes':0,'wake_time_local_avg':'06:30','workouts':0,
    'workout_active_minutes_total':0,'workouts_by_sport':{},
   },
   'change':{
    'days_with_data':1,'steps_per_day_avg':3000,'active_calories_kcal_per_day_avg':100,
    'standing_hours_per_day_avg':1.5,'avg_hr_bpm':3.0,'stress_score_avg':10.0,'spo2_pct_avg':-0.5,
    'sleep_nights':1,'sleep_minutes_per_night_avg':20,'bedtime_std_dev_minutes':30,'workouts':3,
    'bedtime_shift_minutes':60,'wake_time_shift_minutes':45,
   },
   'notes':[
    'Day data on 2 of 7 days.',
    "resting_hr is missing; it comes from the watch's daily summary. avg_hr_bpm and min_hr_bpm come from minute readings.",
    'Previous period: day data on 1 of 7 days.',
    'change leaves out the *_total fields: the periods have day data on 2 and 1 days, so compare per-day averages.',
    'Unfinished sleep sessions left out: 1 in this period, 0 in the previous one.',
   ],
  })

 def test_period_summary_cuts_end_date_after_today(self):
  current='from=2026-10-01&to=2026-10-24'
  previous='from=2026-09-07&to=2026-09-30'
  responses={
   'health/days?'+current:{'days':[day('2026-10-01',steps=9000,distance_m=7000,resting_hr=58),day('2026-10-02',steps=7000,distance_m=5000,resting_hr=60)]},
   'health/sleep?'+current:{'sessions':[]},
   'health/workouts?'+current:{'workouts':[]},
   'health/days?'+previous:{'days':[day('2026-09-29',steps=5000,distance_m=4000,resting_hr=60),day('2026-09-30',steps=6000,distance_m=4500,resting_hr=60)]},
   'health/sleep?'+previous:{'sessions':[]},
   'health/workouts?'+previous:{'workouts':[]},
  }
  with patch.object(main,'call',side_effect=phone(responses)) as api:
   result=main.health_period_summary('2026-10-01','2026-10-31')
  self.assertEqual(api.call_count,6)
  self.assertEqual(result['period'],{'start':'2026-10-01','end':'2026-10-24','days':24})
  self.assertEqual(result['previous_period'],{'start':'2026-09-07','end':'2026-09-30','days':24})
  self.assertEqual(result['change'],{
   'days_with_data':0,'steps_total':5000,'steps_per_day_avg':2500,'distance_km_total':3.5,'resting_hr_bpm_avg':-1.0,
   'sleep_nights':0,'workouts':0,'workout_active_minutes_total':0,
  })
  self.assertEqual(result['notes'],[
   'end_date 2026-10-31 is after today (2026-10-24 on this PC), so the period ends today and is compared with the 24 days before start_date.',
   'end_date is today or later (2026-10-24 on this PC), so its last day may be partial.',
   'Day data on 2 of 24 days.',
   'Previous period: day data on 2 of 24 days.',
  ])

 def test_period_summary_without_data(self):
  empty={'days':[],'sessions':[],'workouts':[]}
  with patch.object(main,'call',return_value=empty) as api:
   result=main.health_period_summary('2026-10-24','2026-10-24')
  self.assertEqual(api.call_count,6)
  self.assertEqual(result['previous_period'],{'start':'2026-10-23','end':'2026-10-23','days':1})
  self.assertEqual(result['current']['steps_total'],None)
  self.assertEqual((result['current']['bedtime_local_avg'],result['current']['bedtime_std_dev_minutes']),(None,None))
  self.assertEqual(result['change'],{})
  self.assertEqual(result['notes'],[
   'end_date is today or later (2026-10-24 on this PC), so its last day may be partial.',
   'No health data in this period.',
   'No health data in the previous period, so change is empty.',
  ])

 def test_trend_rising_line(self):
  days=[day(f'2026-10-{d:02d}',resting_hr=59+d) for d in range(1,9)]
  with patch.object(main,'call',return_value={'days':days}) as api:
   result=main.health_trend('resting_hr','2026-10-01','2026-10-14')
  api.assert_called_once_with('health/days?from=2026-10-01&to=2026-10-14')
  self.assertEqual(result,{
   'metric':'resting_hr','unit':'bpm','start':'2026-10-01','end':'2026-10-14','days_in_range':14,'days_with_data':8,
   'mean':63.5,'min':{'date':'2026-10-01','value':60},'max':{'date':'2026-10-08','value':67},
   'first_half_mean':63.0,'second_half_mean':67.0,'slope_per_week':7.0,'slope_standard_error_per_week':0.0,
   'fitted_change':7.0,'direction':'no clear trend',
   'weekly':[{'week_start':'2026-09-28','mean':61.5,'days':4},{'week_start':'2026-10-05','mean':65.5,'days':4}],
   'notes':['Only 8 days have resting_hr; at least 14 are needed to call a direction.','resting_hr is missing on 6 of 14 days.'],
  })

 def test_trend_falling_with_noise(self):
  steps=(10400,9550,9800,9350,9700,8850,9100,9150,8700,8950,8200,8450,8000,8450,7800,7550)
  days=[day(f'2026-09-{d:02d}',steps=s) for d,s in enumerate(steps,1)]
  with patch.object(main,'call',return_value={'days':days}):
   result=main.health_trend('steps','2026-09-01','2026-09-20')
  self.assertEqual(
   {k:result[k] for k in ('days_with_data','mean','min','max','first_half_mean','second_half_mean','slope_per_week','slope_standard_error_per_week','fitted_change','direction','notes')},
   {'days_with_data':16,'mean':8875.0,'min':{'date':'2026-09-16','value':7550},'max':{'date':'2026-09-01','value':10400},
    'first_half_mean':9355.0,'second_half_mean':8075.0,'slope_per_week':-1085.0,'slope_standard_error_per_week':103.05,
    'fitted_change':-2325.0,'direction':'down','notes':['steps is missing on 4 of 20 days.']},
  )

 def test_trend_two_standard_errors_is_not_enough_for_14_days(self):
  values=(60.0,63.3,58.6,62.0,58.3,63.6,61.9,61.2,65.6,60.9,64.2,60.5,65.8,64.2)
  days=[day(f'2026-09-{d:02d}',avg_hr=v) for d,v in enumerate(values,1)]
  with patch.object(main,'call',return_value={'days':days}):
   result=main.health_trend('avg_hr','2026-09-01','2026-09-14')
  # slope/SE is 2.06: above 2 but below t(0.975, 12 df) = 2.179.
  self.assertEqual(
   {k:result[k] for k in ('slope_per_week','slope_standard_error_per_week','direction','notes')},
   {'slope_per_week':2.04,'slope_standard_error_per_week':0.99,'direction':'no clear trend','notes':[]},
  )

 def test_trend_leaves_out_today_and_later(self):
  days=[day(f'2026-10-{d:02d}',steps=8000+100*d) for d in range(10,24)]+[day('2026-10-24',steps=900)]
  with patch.object(main,'call',return_value={'days':days}) as api:
   result=main.health_trend('steps','2026-10-10','2026-10-31')
  api.assert_called_once_with('health/days?from=2026-10-10&to=2026-10-31')
  self.assertEqual(
   {k:result[k] for k in ('days_in_range','days_with_data','mean','min','max','first_half_mean','second_half_mean','slope_per_week','direction','notes')},
   {'days_in_range':22,'days_with_data':14,'mean':9650.0,'min':{'date':'2026-10-10','value':9000},'max':{'date':'2026-10-23','value':10300},
    'first_half_mean':9300.0,'second_half_mean':10000.0,'slope_per_week':700.0,'direction':'up',
    'notes':['Today (2026-10-24 on this PC) and later days are left out: today may be partial.']},
  )

 def test_tool_descriptions(self):
  for metric in main._TREND_UNITS:self.assertIn(metric,main.health_trend.__doc__)
  self.assertIn('naps included',main.health_trend.__doc__)
  self.assertIn('same number of days just before start_date',main.health_period_summary.__doc__)

 def test_trend_noise_is_not_a_trend(self):
  days=[day(f'2026-10-{d:02d}',steps=6000 if d%2 else 10000) for d in range(1,9)]
  with patch.object(main,'call',return_value={'days':days}):
   result=main.health_trend('steps','2026-10-01','2026-10-08')
  self.assertEqual(
   {k:result[k] for k in ('unit','mean','slope_per_week','slope_standard_error_per_week','fitted_change','direction','notes')},
   {'unit':'steps','mean':8000.0,'slope_per_week':1333.33,'slope_standard_error_per_week':2434.32,'fitted_change':1333.3,'direction':'no clear trend','notes':['Only 8 days have steps; at least 14 are needed to call a direction.']},
  )

 def test_trend_too_sparse_or_missing(self):
  days=[day('2026-10-01',avg_hr=70,min_hr=50),day('2026-10-02',avg_hr=72,min_hr=51),day('2026-10-03',avg_hr=71,min_hr=49)]
  with patch.object(main,'call',return_value={'days':days}):
   sparse=main.health_trend('avg_hr','2026-10-01','2026-10-31')
   missing=main.health_trend('resting_hr','2026-09-01','2026-09-30')
  self.assertEqual((sparse['days_with_data'],sparse['mean'],sparse['slope_per_week'],sparse['direction']),(3,71.0,None,None))
  self.assertEqual(sparse['notes'],[
   'Today (2026-10-24 on this PC) and later days are left out: today may be partial.',
   'Only 3 days have avg_hr; at least 7 are needed for a trend.',
  ])
  self.assertEqual(missing,{
   'metric':'resting_hr','unit':'bpm','start':'2026-09-01','end':'2026-09-30','days_in_range':30,'days_with_data':0,
   'notes':['No resting_hr values in this range.',"resting_hr comes from the watch's daily summary; min_hr and avg_hr come from minute readings and may be present."],
  })

 def test_sleep_analysis(self):
  sessions=[
   night('2026-10-12T23:30','2026-10-13T07:00',420,(84,252,63,21)),
   night('2026-10-14T00:30','2026-10-14T07:30',330,(60,200,50,20)),
   night('2026-10-14T14:00','2026-10-14T15:00',60),
   night('2026-10-17T01:00','2026-10-17T09:00',450),
   night('2026-10-18T23:00','2026-10-19T03:00',240,finished=False),
  ]
  with patch.object(main,'call',return_value={'sessions':sessions}) as api:
   result=main.health_sleep_analysis('2026-10-12','2026-10-18')
  api.assert_called_once_with('health/sleep?from=2026-10-12&to=2026-10-18')
  self.assertEqual(result,{
   'start':'2026-10-12','end':'2026-10-18','nights':3,'unfinished_sessions':1,'extra_sessions':1,
   'duration_minutes':{'avg':400,'median':420,'std_dev':51,'shortest':{'date':'2026-10-14','minutes':330},'longest':{'date':'2026-10-17','minutes':450}},
   'bedtime_local':{'avg':'00:20','std_dev_minutes':37,'earliest':'23:30','latest':'01:00'},
   'wake_time_local':{'avg':'07:50','std_dev_minutes':51,'earliest':'07:00','latest':'09:00'},
   'stage_pct':{'deep':19.2,'light':60.3,'rem':15.1,'awake':5.5},'stage_nights':2,
   'short_nights':{'threshold_minutes':360,'count':1,'dates':['2026-10-14']},
   'weekday_nights_minutes_avg':375,'weekend_nights_minutes_avg':450,'notes':[],
  })

 def test_sleep_analysis_without_nights(self):
  with patch.object(main,'call',return_value={'sessions':[]}):
   result=main.health_sleep_analysis('2026-10-01','2026-10-07',420)
  self.assertEqual(result,{'start':'2026-10-01','end':'2026-10-07','nights':0,'unfinished_sessions':0,'extra_sessions':0,'notes':['No finished sleep sessions in this range.']})

 def test_sleep_analysis_averages_wake_times_across_midnight(self):
  sessions=[night('2026-10-05T16:00','2026-10-05T23:40',460),night('2026-10-06T16:30','2026-10-07T00:20',470)]
  with patch.object(main,'call',return_value={'sessions':sessions}):
   result=main.health_sleep_analysis('2026-10-05','2026-10-07')
  # An arithmetic mean of 23:40 and 00:20 would be 12:00.
  self.assertEqual(result['wake_time_local'],{'avg':'00:00','std_dev_minutes':20,'earliest':'23:40','latest':'00:20'})
  self.assertEqual(result['bedtime_local'],{'avg':'16:15','std_dev_minutes':15,'earliest':'16:00','latest':'16:30'})

 def test_invalid_arguments_do_not_call_api(self):
  cases=(
   (main.health_period_summary,('2026-10-08','2026-10-01'),'start_date must be on or before end_date'),
   (main.health_period_summary,('2026-1-01','2026-10-01'),'start_date must be a valid date'),
   (main.health_period_summary,('2026-10-25','2026-10-31'),'start_date is after today'),
   (main.health_trend,('temperature','2026-10-01','2026-10-31'),'metric must be one of: steps, active_calories'),
   (main.health_trend,('steps','2024-01-01','2025-02-04'),'Date range must not exceed 400 days'),
   (main.health_sleep_analysis,('2026-10-01','2026-02-30'),'end_date must be a valid date'),
   (main.health_sleep_analysis,('2026-10-01','2026-10-07',59),'short_night_minutes must be a whole number from 60 to 720'),
   (main.health_sleep_analysis,('2026-10-01','2026-10-07',721),'short_night_minutes must be'),
   (main.health_sleep_analysis,('2026-10-01','2026-10-07',360.0),'short_night_minutes must be'),
   (main.health_sleep_analysis,('2026-10-01','2026-10-07',True),'short_night_minutes must be'),
  )
  for tool,arguments,message in cases:
   with self.subTest(tool=tool.__name__,arguments=arguments),patch.object(main,'call') as api:
    with self.assertRaisesRegex(ValueError,message):tool(*arguments)
    api.assert_not_called()

if __name__=='__main__':unittest.main()

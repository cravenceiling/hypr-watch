import { z } from 'zod';
import type { ApiCall } from './watch_client.ts';
import { date, dateRange, iso } from './validate.ts';

export const trendUnits: Record<string, string> = {
  steps:'steps', active_calories:'kcal', calories:'kcal', distance_m:'m', standing_hours:'hours',
  resting_hr:'bpm', avg_hr:'bpm', min_hr:'bpm', max_hr:'bpm', avg_spo2:'%', min_spo2:'%',
  avg_stress:'watch stress score', max_stress:'watch stress score', sleep_minutes:'minutes',
  deep_minutes:'minutes', light_minutes:'minutes', rem_minutes:'minutes', awake_minutes:'minutes',
};
const t95 = [[12,2.179],[13,2.160],[14,2.145],[15,2.131],[16,2.120],[17,2.110],[18,2.101],[19,2.093],[20,2.086],[21,2.080],[22,2.074],[23,2.069],[24,2.064],[25,2.060],[26,2.056],[27,2.052],[28,2.048],[29,2.045],[30,2.042],[40,2.021],[60,2.000],[120,1.980]];
const sum = (values: number[]): number => values.reduce((a,b) => a+b, 0);
const mean = (values: number[]): number | null => values.length ? sum(values)/values.length : null;
const mod = (n: number, d: number): number => ((n % d) + d) % d;

export function round(value: number | null, digits: number | null = 1): number | null {
  if (value === null) return null;
  // Python rounds the exact binary float to a decimal place, with ties to even.
  const bytes = Buffer.alloc(8); bytes.writeDoubleBE(Math.abs(value));
  const bits = bytes.readBigUInt64BE();
  const exponent = Number((bits >> 52n) & 2047n);
  const mantissa = (bits & ((1n << 52n)-1n)) + (exponent ? 1n << 52n : 0n);
  const power = exponent ? exponent - 1075 : -1074;
  let numerator = mantissa * 10n ** BigInt(digits ?? 0), denominator = 1n;
  if (power >= 0) numerator <<= BigInt(power); else denominator <<= BigInt(-power);
  let whole = numerator / denominator;
  const remainder = numerator % denominator;
  if (remainder*2n > denominator || remainder*2n === denominator && whole%2n === 1n) whole++;
  const result = Number(whole)/10**(digits ?? 0) * Math.sign(value);
  return result === 0 ? 0 : result;
}
const integerRound = (value: number): number => round(value, null) ?? 0;
const clock = (minutes: number): string => {
  const m = mod(integerRound(minutes),1440);
  return `${String(Math.floor(m/60)).padStart(2,'0')}:${String(m%60).padStart(2,'0')}`;
};
export function clockStats(minutes: number[]) {
  if (!minutes.length) return null;
  const angle = Math.atan2(sum(minutes.map(m => Math.sin(m*Math.PI/720))), sum(minutes.map(m => Math.cos(m*Math.PI/720))));
  const centre = mod(angle*720/Math.PI,1440);
  const offsets = minutes.map(m => mod(m-centre+720,1440)-720);
  return { avg: clock(centre), std_dev_minutes: integerRound(Math.sqrt(sum(offsets.map(o => o*o))/offsets.length)), earliest: clock(centre+Math.min(...offsets)), latest: clock(centre+Math.max(...offsets)) };
}
const sessionSchema = z.object({
  finished:z.boolean().optional(), bed_time:z.number(), wakeup_time:z.number(), tz_offset:z.number(),
  total_minutes:z.number().nullable().optional(), deep_minutes:z.number().nullable().optional(),
  light_minutes:z.number().nullable().optional(), rem_minutes:z.number().nullable().optional(), awake_minutes:z.number().nullable().optional(),
});
const workoutSchema = z.object({sport:z.string(), start:z.number(), end:z.number(), active_seconds:z.number().nullable().optional()});
type Night = {date:number; bed:number; wake:number; minutes:number; stages:(number|null)[]};
type Day = {date:number; values:Record<string,number|null>};
function nights(raw: unknown): {nights:Night[]; unfinished:number; extra:number} {
  const byDate = new Map<number,Night>(); let unfinished=0, extra=0;
  for (const s of z.array(sessionSchema).parse(raw)) {
    if (!s.finished) { unfinished++; continue; }
    const bed = new Date((s.bed_time+s.tz_offset)*1000), wake = new Date((s.wakeup_time+s.tz_offset)*1000);
    const night = {date:Math.floor((s.wakeup_time+s.tz_offset)/86400), bed:bed.getUTCHours()*60+bed.getUTCMinutes(), wake:wake.getUTCHours()*60+wake.getUTCMinutes(), minutes:s.total_minutes ?? Math.floor((s.wakeup_time-s.bed_time)/60), stages:[s.deep_minutes??null,s.light_minutes??null,s.rem_minutes??null,s.awake_minutes??null]};
    const other = byDate.get(night.date);
    if (other) { extra++; if (other.minutes >= night.minutes) continue; }
    byDate.set(night.date, night);
  }
  return {nights:[...byDate.values()].sort((a,b)=>a.date-b.date),unfinished,extra};
}
function days(raw: unknown): Day[] {
  return z.array(z.record(z.string(),z.unknown())).parse(raw).map(d => ({
    date:date('date',d.date), values:Object.fromEntries(Object.keys(trendUnits).map(key => [key,z.number().nullable().parse(d[key] ?? null)])),
  }));
}
function periodStats(daily: Day[], ns: Night[], workouts: z.infer<typeof workoutSchema>[]) {
  const values = (key:string):number[] => daily.flatMap(d => d.values[key] == null ? [] : [d.values[key]]);
  const avg = (key:string,digits:number|null=1) => round(mean(values(key)),digits);
  const steps=values('steps'), distance=values('distance_m');
  const bySport:Record<string,{count:number;active_minutes:number}> = {};
  for (const w of workouts) {
    const sport = bySport[w.sport] ??= {count:0,active_minutes:0};
    sport.count++; sport.active_minutes+=integerRound((w.active_seconds ?? w.end-w.start)/60);
  }
  const bedtime=clockStats(ns.map(n=>n.bed)), wake=clockStats(ns.map(n=>n.wake));
  return {
    days_with_data:daily.length, steps_total:steps.length?sum(steps):null, steps_per_day_avg:round(mean(steps),null),
    active_calories_kcal_per_day_avg:avg('active_calories',null), distance_km_total:distance.length?round(sum(distance)/1000,2):null,
    standing_hours_per_day_avg:avg('standing_hours'), resting_hr_bpm_avg:avg('resting_hr'), avg_hr_bpm:avg('avg_hr'),
    min_hr_bpm:values('min_hr').length?Math.min(...values('min_hr')):null, max_hr_bpm:values('max_hr').length?Math.max(...values('max_hr')):null,
    stress_score_avg:avg('avg_stress'), spo2_pct_avg:avg('avg_spo2'), sleep_nights:ns.length, sleep_minutes_per_night_avg:round(mean(ns.map(n=>n.minutes)),null),
    bedtime_local_avg:bedtime?.avg??null, bedtime_std_dev_minutes:bedtime?.std_dev_minutes??null, wake_time_local_avg:wake?.avg??null,
    workouts:workouts.length, workout_active_minutes_total:sum(Object.values(bySport).map(s=>s.active_minutes)), workouts_by_sport:bySport,
  };
}
const compared = ['days_with_data','steps_total','steps_per_day_avg','active_calories_kcal_per_day_avg','distance_km_total','standing_hours_per_day_avg','resting_hr_bpm_avg','avg_hr_bpm','stress_score_avg','spo2_pct_avg','sleep_nights','sleep_minutes_per_night_avg','bedtime_std_dev_minutes','workouts','workout_active_minutes_total'] as const;
const clockChange = (current:string,previous:string):number => {
  const minutes = (s:string)=>Number(s.slice(0,2))*60+Number(s.slice(3));
  return mod(minutes(current)-minutes(previous)+720,1440)-720;
};
const weekday = (day:number):number => mod(day+3,7);

export function createInsights(call:ApiCall, today:()=>string) {
  const partialNote = (end:number):string[] => end >= date('today',today()) ? [`end_date is today or later (${today()} on this PC), so its last day may be partial.`] : [];
  return {
    async health_period_summary(a:Record<string,unknown>) {
      dateRange(a.start_date,a.end_date);
      const start=date('start_date',a.start_date), requestedEnd=date('end_date',a.end_date), now=date('today',today());
      if (start>now) throw new Error(`start_date is after today (${today()} on this PC), so there is no data yet.`);
      const end=Math.min(requestedEnd,now), length=end-start+1, beforeEnd=start-1, beforeStart=beforeEnd-length+1;
      const stats = async(first:number,last:number) => {
        const query=`from=${iso(first)}&to=${iso(last)}`;
        const ns=nights((await call('health/sleep?'+query)).sessions);
        const daily=days((await call('health/days?'+query)).days);
        const workouts=z.array(workoutSchema).parse((await call('health/workouts?'+query)).workouts);
        return {stats:periodStats(daily,ns.nights,workouts),unfinished:ns.unfinished};
      };
      const c=await stats(start,end), p=await stats(beforeStart,beforeEnd), current=c.stats, previous=p.stats;
      const empty = (s:typeof current) => !s.days_with_data && !s.sleep_nights && !s.workouts;
      const notes = requestedEnd>now ? [`end_date ${a.end_date} is after today (${today()} on this PC), so the period ends today and is compared with the ${length} days before start_date.`] : [];
      notes.push(...partialNote(end));
      if (empty(current)) notes.push('No health data in this period.');
      else if (current.days_with_data<length) notes.push(`Day data on ${current.days_with_data} of ${length} days.`);
      if (current.days_with_data && current.resting_hr_bpm_avg===null) notes.push("resting_hr is missing; it comes from the watch's daily summary. avg_hr_bpm and min_hr_bpm come from minute readings.");
      const change:Record<string,number|null>={};
      if (empty(previous)) notes.push('No health data in the previous period, so change is empty.');
      else {
        if (previous.days_with_data<length) notes.push(`Previous period: day data on ${previous.days_with_data} of ${length} days.`);
        const totals=current.days_with_data===previous.days_with_data;
        if (!totals) notes.push(`change leaves out the *_total fields: the periods have day data on ${current.days_with_data} and ${previous.days_with_data} days, so compare per-day averages.`);
        for (const key of compared) {
          const cv=current[key], pv=previous[key];
          if ((totals || !key.endsWith('_total')) && cv!==null && pv!==null) change[key]=round(cv-pv,2);
        }
        for (const key of ['bedtime_local_avg','wake_time_local_avg'] as const) {
          const cv=current[key],pv=previous[key];
          if (cv!==null && pv!==null) change[key.replace('_local_avg','_shift_minutes')]=clockChange(cv,pv);
        }
      }
      if (c.unfinished || p.unfinished) notes.push(`Unfinished sleep sessions left out: ${c.unfinished} in this period, ${p.unfinished} in the previous one.`);
      return {period:{start:a.start_date,end:iso(end),days:length},previous_period:{start:iso(beforeStart),end:iso(beforeEnd),days:length},current,previous,change,notes};
    },
    async health_trend(a:Record<string,unknown>) {
      const metric=a.metric;
      if (typeof metric!=='string' || !Object.hasOwn(trendUnits,metric)) throw new Error('metric must be one of: '+Object.keys(trendUnits).join(', ')+'.');
      const query=dateRange(a.start_date,a.end_date),start=date('start_date',a.start_date),end=date('end_date',a.end_date),now=date('today',today()),last=Math.min(end,now-1);
      const points=days((await call('health/days?'+query)).days).flatMap(d => d.values[metric]!=null && d.date<=last ? [{day:d.date,value:d.values[metric]}] : []);
      const counted=Math.max(last-start+1,0),values=points.map(p=>p.value);
      const result:Record<string,unknown>={metric,unit:trendUnits[metric],start:a.start_date,end:a.end_date,days_in_range:end-start+1,days_with_data:points.length};
      const notes=end>=now?[`Today (${today()} on this PC) and later days are left out: today may be partial.`]:[];
      if (!points.length) {
        notes.push(`No ${metric} values in this range.`);
        if (metric==='resting_hr') notes.push("resting_hr comes from the watch's daily summary; min_hr and avg_hr come from minute readings and may be present.");
        return {...result,notes};
      }
      const middle=start+Math.floor(counted/2),weeks=new Map<number,number[]>();
      for (const p of points) { const week=p.day-weekday(p.day); const list=weeks.get(week)??[];list.push(p.value);weeks.set(week,list); }
      const low=points.reduce((a,b)=>b.value<a.value?b:a),high=points.reduce((a,b)=>b.value>a.value?b:a);
      Object.assign(result,{mean:round(mean(values)),min:{date:iso(low.day),value:low.value},max:{date:iso(high.day),value:high.value},first_half_mean:round(mean(points.filter(p=>p.day<middle).map(p=>p.value))),second_half_mean:round(mean(points.filter(p=>p.day>=middle).map(p=>p.value))),slope_per_week:null,slope_standard_error_per_week:null,fitted_change:null,direction:null,weekly:[...weeks].sort((a,b)=>a[0]-b[0]).map(([week,v])=>({week_start:iso(week),mean:round(mean(v)),days:v.length}))});
      if (points.length<7) { notes.push(`Only ${points.length} days have ${metric}; at least 7 are needed for a trend.`); return {...result,notes}; }
      const xs=points.map(p=>p.day-start),mx=sum(xs)/xs.length,my=sum(values)/values.length;
      const sxx=sum(xs.map(x=>(x-mx)**2)),slope=sum(xs.map((x,i)=>(x-mx)*(values[i]-my)))/sxx;
      const residual=sum(xs.map((x,i)=>(values[i]-my-slope*(x-mx))**2)),error=Math.sqrt(residual/(xs.length-2)/sxx),enough=points.length>=14;
      const critical=t95.findLast(([df])=>df<=points.length-2)?.[1]??Infinity;
      const clear=enough && slope!==0 && Math.abs(slope)>=critical*error;
      Object.assign(result,{slope_per_week:round(slope*7,2),slope_standard_error_per_week:round(error*7,2),fitted_change:round(slope*(xs[xs.length-1]-xs[0])),direction:clear?(slope>0?'up':'down'):'no clear trend'});
      if (!enough) notes.push(`Only ${points.length} days have ${metric}; at least 14 are needed to call a direction.`);
      if (points.length<counted) notes.push(`${metric} is missing on ${counted-points.length} of ${counted} days.`);
      return {...result,notes};
    },
    async health_sleep_analysis(a:Record<string,unknown>) {
      const threshold=a.short_night_minutes??360;
      if (typeof threshold!=='number' || !Number.isInteger(threshold) || threshold<60 || threshold>720) throw new Error('short_night_minutes must be a whole number from 60 to 720.');
      const query=dateRange(a.start_date,a.end_date),ns=nights((await call('health/sleep?'+query)).sessions),notes=partialNote(date('end_date',a.end_date));
      const result={start:a.start_date,end:a.end_date,nights:ns.nights.length,unfinished_sessions:ns.unfinished,extra_sessions:ns.extra},list=ns.nights;
      if (!list.length) { notes.push('No finished sleep sessions in this range.');return {...result,notes}; }
      const minutes=list.map(n=>n.minutes),avg=sum(minutes)/minutes.length,sorted=[...minutes].sort((a,b)=>a-b);
      const shortest=list.reduce((a,b)=>b.minutes<a.minutes?b:a),longest=list.reduce((a,b)=>b.minutes>a.minutes?b:a);
      const staged=list.map(n=>n.stages).filter((s):s is number[]=>s.every(v=>v!==null) && sum(s)>0),stageTotal=sum(staged.map(sum));
      const short=list.filter(n=>n.minutes<threshold).map(n=>iso(n.date));
      if (list.length<3) notes.push(`Only ${list.length} nights; regularity figures need more nights to mean much.`);
      if (!staged.length) notes.push('No night has all four stage totals, so stage_pct is null.');
      return {...result,duration_minutes:{avg:integerRound(avg),median:integerRound((sorted[Math.floor((sorted.length-1)/2)]+sorted[Math.floor(sorted.length/2)])/2),std_dev:integerRound(Math.sqrt(sum(minutes.map(m=>(m-avg)**2))/minutes.length)),shortest:{date:iso(shortest.date),minutes:shortest.minutes},longest:{date:iso(longest.date),minutes:longest.minutes}},bedtime_local:clockStats(list.map(n=>n.bed)),wake_time_local:clockStats(list.map(n=>n.wake)),stage_pct:staged.length?Object.fromEntries(['deep','light','rem','awake'].map((key,i)=>[key,round(sum(staged.map(s=>s[i]))*100/stageTotal,1)])):null,stage_nights:staged.length,short_nights:{threshold_minutes:threshold,count:short.length,dates:short},weekday_nights_minutes_avg:round(mean(list.filter(n=>weekday(n.date)<5).map(n=>n.minutes)),null),weekend_nights_minutes_avg:round(mean(list.filter(n=>weekday(n.date)>=5).map(n=>n.minutes)),null),notes};
    },
  };
}

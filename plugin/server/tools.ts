import { openSync, readSync, closeSync, statSync, realpathSync, existsSync } from 'node:fs';
import { resolve, extname } from 'node:path';
import { homedir } from 'node:os';
import { z } from 'zod';
import type { ApiCall } from './watch_client.ts';
import { createInsights } from './insights.ts';
import * as v from './validate.ts';

type Handler=(args:Record<string,unknown>)=>Promise<Record<string,unknown>>;
export function createTools(call:ApiCall,handoff:{takeOver:()=>Promise<Record<string,unknown>>;release:()=>Promise<Record<string,unknown>>},today=()=>{
  const now=new Date();return `${now.getFullYear()}-${String(now.getMonth()+1).padStart(2,'0')}-${String(now.getDate()).padStart(2,'0')}`;
}):Record<string,Handler> {
  const routes:Record<string,[string,string?]>={
    watch_status:['status'],list_notes:['notes'],read_watch_reminders:['watch/reminders'],sync_watch_notes:['watch/sync','POST'],
    device_status:['device'],find_phone_stop:['device/find-phone/stop','POST'],sync_watch_time:['device/time-sync','POST'],weather_get:['weather'],weather_refresh:['weather/refresh','POST'],music_status:['music'],get_sleep_schedule:['alarms/sleep-mode'],get_watch_settings:['watch-settings'],get_live_heart_rate:['heart-rate/live'],get_favourite_contacts:['contacts'],get_quick_replies:['quick-replies'],sync_health:['health/sync','POST'],get_health_profile:['health/profile'],get_notification_settings:['notifications'],
  };
  const tools:Record<string,Handler>={...createInsights(call,today)};
  for(const [name,[path,method='GET']] of Object.entries(routes)) tools[name]=()=>call(path,method,method==='GET'?undefined:{});
  Object.assign(tools,{
    connect_watch:()=>handoff.takeOver(),disconnect_watch:()=>handoff.release(),
    create_note:async(a:Record<string,unknown>)=>{
      const data:Record<string,unknown>={title:a.title,body:a.body??'',watch_enabled:a.send_to_watch??false};
      if(a.watch_title) data.watch_title=a.watch_title;return call('notes','POST',data);
    },
    update_note:async(a:Record<string,unknown>)=>call('notes/'+v.noteId.parse(a.note_id),'PATCH',v.nonNull({title:a.title,body:a.body,done:a.done,watch_title:a.watch_title,watch_enabled:a.send_to_watch})),
    delete_note:async(a:Record<string,unknown>)=>call('notes/'+v.noteId.parse(a.note_id),'DELETE',{}),
    find_watch:async(a:Record<string,unknown>)=>{v.bool('on',a.on);return call('device/find-watch','POST',{on:a.on});},
    music_set_enabled:async(a:Record<string,unknown>)=>{v.bool('enabled',a.enabled);return call('music/settings','PUT',{enabled:a.enabled});},
    weather_set:async(a:Record<string,unknown>)=>{
      const {units,enabled,location_mode,lat,lon,name}=a,data:Record<string,unknown>={};
      if(units!=null) {if(typeof units!=='string' || !['metric','imperial'].includes(units)) throw new Error('units must be metric or imperial.');data.units=units;}
      if(enabled!=null) {v.bool('enabled',enabled);data.enabled=enabled;}
      if(location_mode==null) {if(lat!=null || lon!=null || name!=null) throw new Error('lat, lon, and name require location_mode.');}
      else {
        if(typeof location_mode!=='string' || !['device','manual'].includes(location_mode)) throw new Error('location_mode must be device or manual.');
        const location:Record<string,unknown>={mode:location_mode};
        if(name!=null) {
          if(typeof name!=='string') throw new Error('name must be text.');
          const clean=name.trim();if(clean.length<1 || clean.length>32 || v.control(clean)) throw new Error('name must be 1 to 32 characters with no control characters.');
          location.name=name;
        }
        if(location_mode==='device') {if(lat!=null || lon!=null) throw new Error('device location takes no coordinates.');}
        else {
          if(lat==null || lon==null || name==null) throw new Error('manual location requires lat, lon, and name.');
          for(const [key,value,limit] of [['lat',lat,90],['lon',lon,180]] as const) {
            if(typeof value!=='number' || !Number.isFinite(value) || value < -limit || value>limit) throw new Error(`${key} must be a number between -${limit}.0 and ${limit}.0.`);
            location[key]=value;
          }
        }
        data.location=location;
      }
      return call('weather/settings','PUT',data);
    },
    list_alarms:async(a:Record<string,unknown>)=>{const refresh=a.refresh??false;v.bool('refresh',refresh);return refresh?call('alarms/refresh','POST',{}):call('alarms');},
    create_alarm:async(a:Record<string,unknown>)=>{
      const smart=a.smart??false,enabled=a.enabled??true;v.time('time',a.time);v.bool('smart',smart);v.bool('enabled',enabled);
      const data:Record<string,unknown>={time:a.time,smart,enabled};
      if(a.repeat!=null) {v.repeat(a.repeat);data.repeat=a.repeat;}
      if(a.label!=null) {v.label(a.label);data.label=a.label;}return call('alarms','POST',data);
    },
    update_alarm:async(a:Record<string,unknown>)=>{
      const id=v.alarmId(a.alarm_id);
      if(a.time!=null) v.time('time',a.time);if(a.repeat!=null) v.repeat(a.repeat);if(a.label!=null) v.label(a.label);
      if(a.smart!=null) v.bool('smart',a.smart);if(a.enabled!=null) v.bool('enabled',a.enabled);
      const data=v.nonNull({time:a.time,repeat:a.repeat,label:a.label,smart:a.smart,enabled:a.enabled});
      if(!Object.keys(data).length) throw new Error('Give at least one alarm field to change.');
      if(a.expect!=null) {v.expect(a.expect);data.expect=a.expect;}return call('alarms/'+id,'PUT',data);
    },
    delete_alarm:async(a:Record<string,unknown>)=>{const id=v.alarmId(a.alarm_id);if(a.expect!=null) v.expect(a.expect);return call('alarms/'+id,'DELETE',a.expect==null?{}:{expect:a.expect});},
    set_sleep_schedule:async(a:Record<string,unknown>)=>{
      if(a.enabled!=null) v.bool('enabled',a.enabled);if(a.start!=null) v.time('start',a.start);if(a.end!=null) v.time('end',a.end);
      const data=v.nonNull({enabled:a.enabled,start:a.start,end:a.end});
      if(!Object.keys(data).length) throw new Error('Give enabled, start, or end.');
      if(a.start!=null && a.end!=null && a.start===a.end) throw new Error('start and end must differ.');
      return call('alarms/sleep-mode','PUT',data);
    },
    set_watch_settings:async(a:Record<string,unknown>)=>{v.watchSettings(a.settings);return call('watch-settings','PUT',a.settings);},
    start_live_heart_rate:async()=>call('heart-rate/live','POST',{on:true}),
    list_watch_faces:async(a:Record<string,unknown>)=>{const refresh=a.refresh??false;v.bool('refresh',refresh);return call(refresh?'watch-faces?refresh=true':'watch-faces');},
    set_watch_face:async(a:Record<string,unknown>)=>call('watch-faces/'+v.faceId(a.face_id)+'/activate','POST',{}),
    delete_watch_face:async(a:Record<string,unknown>)=>call('watch-faces/'+v.faceId(a.face_id),'DELETE',{}),
    install_watch_face:async(a:Record<string,unknown>)=>{
      if(typeof a.path!=='string') throw new Error('path must be text.');
      const expanded=resolve(a.path.startsWith('~/')?resolve(homedir(),a.path.slice(2)):a.path),file=existsSync(expanded)?realpathSync(expanded):expanded;
      if(extname(file).toLowerCase()!=='.bin') throw new Error('Watch face path must end in .bin.');
      if(!existsSync(file) || !statSync(file).isFile()) throw new Error('Watch face file does not exist.');
      let fd:number|undefined,data:Buffer;
      try {
        fd=openSync(file,'r');const magic=Buffer.alloc(2),count=readSync(fd,magic,0,2,null);
        if(count!==2 || !magic.equals(Buffer.from([0x5a,0xa5]))) throw new Error('Not a watch face file: it does not start with 5A A5.');
        const rest=Buffer.alloc(5999999);let size=0;
        while(size<rest.length) {const read=readSync(fd,rest,size,rest.length-size,null);if(!read) break;size+=read;}
        data=Buffer.concat([magic,rest.subarray(0,size)]);
      } catch(error) {
        if(error instanceof Error && 'code' in error) throw new Error(`Cannot read watch face file: ${error.message}.`);
        throw error;
      } finally {if(fd!==undefined) closeSync(fd);}
      if(data.length>6000000) throw new Error('Watch face files are limited to 6000000 bytes.');
      return call('watch-faces/upload','POST',{data:data.toString('base64')});
    },
    set_favourite_contacts:async(a:Record<string,unknown>)=>{
      if(!Array.isArray(a.contacts)) throw new Error('contacts must be a list of {name, number}.');
      if(a.contacts.length>10) throw new Error('The watch holds at most 10 favourite contacts.');
      const seen=new Set<string>();
      for(const [i,contact] of a.contacts.entries()) {
        const index=i+1;if(!contact || typeof contact!=='object' || Array.isArray(contact)) throw new Error(`Contact ${index}: send {name, number}.`);
        const name=typeof contact.name==='string'?contact.name.trim():null,number=typeof contact.number==='string'?contact.number.trim():null;
        if(name===null || [...name].length<1 || [...name].length>32 || v.control(name)) throw new Error(`Contact ${index}: use a name of 1 to 32 characters.`);
        if(number===null || number.length>32 || !/^\+?[0-9][0-9 ]*$/.test(number) || number.replace(/[^0-9]/g,'').length>20) throw new Error(`Contact ${index}: use a number of digits, spaces and an optional leading +, at most 20 digits.`);
        const digits=number.replace(/[^0-9]/g,'');if(seen.has(digits)) throw new Error(`Contact ${index}: that number is already in the list.`);seen.add(digits);
      }
      return call('contacts','PUT',{contacts:a.contacts});
    },
    set_quick_replies:async(a:Record<string,unknown>)=>{
      if(!Array.isArray(a.replies)) throw new Error('replies must be a list of strings.');if(a.replies.length>16) throw new Error('The watch holds at most 16 quick replies.');
      for(const [i,reply] of a.replies.entries()) {const clean=typeof reply==='string'?reply.trim():null;if(clean===null || [...clean].length<1 || [...clean].length>30 || v.control(clean)) throw new Error(`Reply ${i+1}: use 1 to 30 characters on one line.`);}
      return call('quick-replies','PUT',{replies:a.replies});
    },
    health_status:async()=>{const health=z.record(z.string(),z.unknown()).parse((await call('status')).health);return {...health,configured:(await call('health/profile')).configured};},
    health_series:async(a:Record<string,unknown>)=>{
      if(!['steps','heart_rate','spo2','stress'].includes(String(a.metric))) throw new Error('metric must be one of: steps, heart_rate, spo2, stress.');
      const bucket=a.bucket_minutes??15;if(typeof bucket!=='number' || ![1,5,15,60].includes(bucket)) throw new Error('bucket_minutes must be one of: 1, 5, 15, 60.');
      v.dateRange(a.start_date,a.end_date);return call(`health/series?metric=${a.metric}&from=${a.start_date}&to=${a.end_date}&bucket=${bucket}`);
    },
    set_health_profile:async(a:Record<string,unknown>)=>{v.date('birthday',a.birthday);return call('health/profile','PUT',a);},
    set_notification_settings:async(a:Record<string,unknown>)=>call('notifications','PUT',{allowed:a.allowed,calls:a.calls}),
  } satisfies Record<string,Handler>);
  for(const suffix of ['days','sleep','samples','workouts']) tools['health_'+suffix]=async a=>call('health/'+suffix+'?'+v.dateRange(a.start_date,a.end_date));
  return tools;
}

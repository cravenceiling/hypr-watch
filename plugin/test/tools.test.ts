import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync, mkdtempSync, writeFileSync, symlinkSync, openSync, ftruncateSync, closeSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import fs from 'node:fs';
import { syncBuiltinESMExports } from 'node:module';
import { z } from 'zod';
import { createTools } from '../server/tools.ts';
import { definitions } from '../server/tool-definitions.ts';
import { round,clockStats } from '../server/insights.ts';
import { dateRange,date,iso } from '../server/validate.ts';
import { Client } from '@modelcontextprotocol/sdk/client/index.js';
import { StdioClientTransport } from '@modelcontextprotocol/sdk/client/stdio.js';
import { InMemoryTransport } from '@modelcontextprotocol/sdk/inMemory.js';
import { createServer } from '../server/main.ts';

const caseSchema=z.object({tool:z.string(),arguments:z.record(z.string(),z.unknown()),today:z.string(),calls:z.array(z.object({path:z.string(),method:z.string(),data:z.unknown(),result:z.record(z.string(),z.unknown()).optional(),error:z.string().optional()})),result:z.unknown().optional(),error:z.string().optional()});
const cases=z.array(caseSchema).parse(JSON.parse(readFileSync(new URL('./tool-cases.json',import.meta.url),'utf8')));
const handoff={takeOver:async()=>({}),release:async()=>({})};
for(const [index,c] of cases.entries()) {
  // JSON cannot distinguish Python's 360.0 from 360; MCP integers accept both spellings.
  if(c.tool==='health_sleep_analysis' && c.arguments.short_night_minutes===360 && c.error) continue;
  test(`Python parity ${index+1} ${c.tool}`,async()=>{
    let count=0;
    const tools=createTools(async(path,method='GET',data)=>{
      const expected=c.calls[count++];assert.ok(expected,'unexpected phone request');
      assert.deepEqual({path,method,data:data??null},{path:expected.path,method:expected.method,data:expected.data});
      if(expected.error) throw new Error(expected.error);
      return structuredClone(expected.result??{});
    },handoff,()=>c.today);
    if(c.error) await assert.rejects(tools[c.tool](c.arguments),{message:c.error});
    else assert.deepEqual(await tools[c.tool](c.arguments),c.result);
    assert.equal(count,c.calls.length);
  });
}
test('all 50 tool names, descriptions, annotations, arguments and defaults match Python',()=>{
  const original=z.array(z.object({name:z.string(),description:z.string(),kind:z.string(),args:z.array(z.object({name:z.string(),required:z.boolean(),default:z.unknown().optional()}))})).parse(JSON.parse(readFileSync(new URL('./tool-metadata.json',import.meta.url),'utf8')));
  assert.equal(definitions.length,50);
  for(const [index,d] of definitions.entries()) {
    const old=original[index];assert.equal(d.name,old.name);assert.equal(d.description,old.description);
    assert.deepEqual(d.annotations,{readOnlyHint:old.kind==='READ',destructiveHint:old.kind==='DELETE',openWorldHint:false});
    assert.deepEqual(Object.keys(d.inputSchema.shape),old.args.map(a=>a.name));
    for(const a of old.args) {
      const shape:Record<string,z.ZodType>=d.inputSchema.shape;const schema=shape[a.name];assert.ok(schema);
      assert.equal(schema.safeParse(undefined).success,!a.required);
      if(!a.required) assert.deepEqual(schema.parse(undefined),a.default);
    }
  }
});
test('rounding matches Python for ties, binary floats, negative values and midnight',()=>{
  for(const [value,digits,expected] of [[2.5,0,2],[3.5,0,4],[-2.5,0,-2],[2.675,2,2.67],[0.15,1,0.1],[0.25,1,0.2],[1.25,1,1.2],[-1.25,1,-1.2],[123.455,2,123.45]]) assert.equal(round(value,digits),expected);
  assert.deepEqual(clockStats([1420,20]),{avg:'00:00',std_dev_minutes:20,earliest:'23:40',latest:'00:20'});
  assert.equal(dateRange('2024-02-29','2025-04-03'),'from=2024-02-29&to=2025-04-03');
  assert.throws(()=>dateRange('2024-02-29','2025-04-04'),/400 days/);
  assert.equal(iso(date('date','2024-03-01')-1),'2024-02-29');
  assert.throws(()=>date('date','2026-02-30'),/valid date/);
});
test('notes and watch tool routes retain defaults, omitted fields and handoff results',async()=>{
  const calls:unknown[][]=[];
  const tools=createTools(async(path,method='GET',data)=>{calls.push([path,method,data??null]);return {ok:true};},{takeOver:async()=>({connected:true}),release:async()=>({released:true})});
  await tools.list_notes({});await tools.create_note({title:'Example'});await tools.create_note({title:'Example',body:'Body',watch_title:'Short',send_to_watch:true});
  await tools.update_note({note_id:'42',done:false,body:'',title:null,send_to_watch:false});await tools.delete_note({note_id:'42'});
  await tools.read_watch_reminders({});await tools.sync_watch_notes({});
  assert.deepEqual(await tools.connect_watch({}),{connected:true});assert.deepEqual(await tools.disconnect_watch({}),{released:true});
  assert.deepEqual(calls,[['notes','GET',null],['notes','POST',{title:'Example',body:'',watch_enabled:false}],['notes','POST',{title:'Example',body:'Body',watch_title:'Short',watch_enabled:true}],['notes/42','PATCH',{body:'',done:false,watch_enabled:false}],['notes/42','DELETE',{}],['watch/reminders','GET',null],['watch/sync','POST',{}]]);
});
test('Python full-match validation rejects trailing newlines before calling the phone',async()=>{
  const tools=createTools(async()=>{assert.fail('invalid inputs must not call the phone');},handoff);
  await assert.rejects(tools.create_alarm({time:'07:30\n'}),/time must be a time/);
  await assert.rejects(tools.delete_alarm({alarm_id:'42\n'}),/alarm_id must be/);
  await assert.rejects(tools.set_watch_face({face_id:'42\n'}),/face_id must be/);
});
test('note argument schemas and handlers reject invalid IDs before calling the phone',async()=>{
  const tools=createTools(async()=>assert.fail('invalid note IDs must not call the phone'),handoff);
  for(const tool of ['update_note','delete_note']) {
    const definition=definitions.find(d=>d.name===tool);assert.ok(definition);
    for(const note_id of ['', '42\n', 'a b', '../status', 'id?x=1', 'id#fragment']) {
      const result=definition.inputSchema.safeParse({note_id});assert.equal(result.success,false);
      if(!result.success) assert.match(result.error.message,/note_id must/);
      await assert.rejects(tools[tool]({note_id}),/note_id must/);
    }
    assert.equal(definition.inputSchema.safeParse({note_id:'f740d331-8c60-47d5-a83f-e0efcf36592e'}).success,true);
  }
});
test('face files support uppercase, symlinks and bounded reads with validation before API',async()=>{
  const dir=mkdtempSync(join(tmpdir(),'hypr-face-'));
  const calls:unknown[]=[];
  const tools=createTools(async(...args)=>{calls.push(args);return {ok:true};},handoff);
  try {
    const content=Buffer.from([0x5a,0xa5,1,2,3]),file=join(dir,'Custom.BIN');writeFileSync(file,content);symlinkSync(file,join(dir,'link.bin'));
    for(const path of [file,join(dir,'link.bin')]) assert.deepEqual(await tools.install_watch_face({path}),{ok:true});
    assert.deepEqual(calls[0],['watch-faces/upload','POST',{data:content.toString('base64')}]);
    const text=join(dir,'face.txt');writeFileSync(text,content);symlinkSync(text,join(dir,'disguised.bin'));
    const huge=join(dir,'huge.bin');writeFileSync(huge,content);const fd=openSync(huge,'r+');ftruncateSync(fd,50000000);closeSync(fd);
    writeFileSync(join(dir,'bad.bin'),'PK');
    for(const [path,message] of [[join(dir,'missing.bin'),'does not exist'],[text,'must end in'],[join(dir,'disguised.bin'),'must end in'],[huge,'limited to 6000000'],[join(dir,'bad.bin'),'does not start with 5A A5']]) await assert.rejects(tools.install_watch_face({path}),new RegExp(message));
    const sizes:number[]=[],read=fs.readSync;
    const spy=test.mock.method(fs,'readSync',(...args:unknown[])=>{
      if(typeof args[3]==='number') sizes.push(args[3]);
      return Reflect.apply(read,fs,args);
    });
    syncBuiltinESMExports();
    try {
      await assert.rejects(tools.install_watch_face({path:huge}),/limited to 6000000/);assert.deepEqual(sizes,[2,5999999]);sizes.length=0;
      await assert.rejects(tools.install_watch_face({path:join(dir,'bad.bin')}),/does not start with 5A A5/);assert.deepEqual(sizes,[2]);
    } finally {spy.mock.restore();syncBuiltinESMExports();}
    const priorHome=process.env.HOME;process.env.HOME=dir;
    try {await tools.install_watch_face({path:'~/Custom.BIN'});}
    finally {if(priorHome===undefined) delete process.env.HOME;else process.env.HOME=priorHome;}
    assert.equal(calls.length,3);
  } finally {rmSync(dir,{recursive:true,force:true});}
});
test('sleep dates and clock statistics use each session timezone across UTC midnight',async()=>{
  const bed_time=Date.parse('2026-01-01T17:15:00Z')/1000,wakeup_time=Date.parse('2026-01-02T00:15:00Z')/1000;
  const tools=createTools(async()=>({sessions:[{bed_time,wakeup_time,tz_offset:14*3600,total_minutes:420,finished:true},{bed_time,wakeup_time,tz_offset:-12*3600,total_minutes:360,finished:true}]}),handoff,()=> '2026-02-01');
  const result=await tools.health_sleep_analysis({start_date:'2026-01-01',end_date:'2026-01-02'});
  assert.equal(result.nights,2);
  assert.deepEqual(result.duration_minutes,{avg:390,median:390,std_dev:30,shortest:{date:'2026-01-01',minutes:360},longest:{date:'2026-01-02',minutes:420}});
  assert.deepEqual(result.bedtime_local,{avg:'06:15',std_dev_minutes:60,earliest:'05:15',latest:'07:15'});
  assert.deepEqual(result.wake_time_local,{avg:'13:15',std_dev_minutes:60,earliest:'12:15',latest:'14:15'});
  assert.equal(result.weekday_nights_minutes_avg,390);assert.equal(result.weekend_nights_minutes_avg,null);
});
test('MCP lists tools and returns the Python dict envelope and error text',async()=>{
  const [a,b]=InMemoryTransport.createLinkedPair();
  const tools=createTools(async path=>{if(path==='device/time-sync') throw new Error('Another watch operation is in progress');return {ok:true};},handoff);
  const server=createServer(tools),client=new Client({name:'test',version:'1'});
  try {
    await server.connect(a);await client.connect(b);
    const list=await client.listTools();assert.equal(list.tools.length,50);
    const result=await client.callTool({name:'watch_status',arguments:{}});
    assert.deepEqual(result.structuredContent,{ok:true});assert.deepEqual(result.content,[{type:'text',text:JSON.stringify({ok:true},null,2)}]);
    const error=await client.callTool({name:'sync_watch_time',arguments:{}});assert.equal(error.isError,true);
    assert.deepEqual(error.content,[{type:'text',text:'Error executing tool sync_watch_time: Another watch operation is in progress'}]);
  } finally {await client.close();await server.close();}
});
test('native TypeScript entry point starts over stdio without contacting a phone',async()=>{
  const transport=new StdioClientTransport({command:process.execPath,args:[new URL('../server/main.ts',import.meta.url).pathname],stderr:'pipe'});
  const client=new Client({name:'stdio-test',version:'1'});
  try {await client.connect(transport);assert.equal((await client.listTools()).tools.length,50);}
  finally {await client.close();}
});

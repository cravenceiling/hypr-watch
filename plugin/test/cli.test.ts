import test from 'node:test';
import assert from 'node:assert/strict';
import { createServer,get } from 'node:http';
import { spawn } from 'node:child_process';
import { mkdtempSync,mkdirSync,writeFileSync,readFileSync,existsSync,rmSync,symlinkSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { once } from 'node:events';
import { parseHost } from '../server/discovery.ts';
import { z } from 'zod';
const cli=new URL('../server/cli.ts',import.meta.url).pathname;
async function run(args:string[],env:NodeJS.ProcessEnv,entry=cli) {
  const child=spawn(process.execPath,[entry,...args],{env,stdio:['ignore','pipe','pipe'],timeout:10000});
  let stdout='',stderr='';child.stdout.setEncoding('utf8').on('data',chunk=>{stdout+=chunk;});child.stderr.setEncoding('utf8').on('data',chunk=>{stderr+=chunk;});
  const [status]=await once(child,'exit');return {stdout,stderr,status};
}
test('CLI host parsing handles IPv4, IPv6 and explicit ports',()=>{
  assert.deepEqual(parseHost('192.168.1.2'),{host:'192.168.1.2',port:8767});assert.deepEqual(parseHost('192.168.1.2:9999'),{host:'192.168.1.2',port:9999});assert.deepEqual(parseHost('[::1]:9876'),{host:'::1',port:9876});
  assert.deepEqual(parseHost('192.168.1.2:443'),{host:'192.168.1.2',port:443});
  for(const value of ['user:password@192.168.1.2','192.168.1.2/path','192.168.1.2?x=1','192.168.1.2:999999','192.168.1.2:0']) assert.throws(()=>parseHost(value));
});
test('setup keeps an unreachable pairing and still registers the selected agent',async()=>{
  const dir=mkdtempSync(join(tmpdir(),'hypr-offline-')),bin=join(dir,'bin'),state=join(dir,'hypr-watch'),log=join(dir,'agent.log');
  mkdirSync(bin);mkdirSync(state);symlinkSync(process.execPath,join(bin,'node'));
  writeFileSync(join(bin,'codex'),`#!/usr/bin/env node
require('node:fs').appendFileSync(process.env.TEST_AGENT_LOG,JSON.stringify(process.argv.slice(2))+'\\n');
`,{mode:0o755});
  const server=createServer();server.listen(0,'127.0.0.1');await once(server,'listening');
  const address=server.address();assert.ok(address && typeof address!=='string');
  await new Promise<void>(resolve=>server.close(()=>resolve()));
  const paired={phone_id:'AQEBAQEBAQEBAQEBAQEBAQ',phone_name:'Offline phone',agent_id:'AgICAgICAgICAgICAgICAg',token:'hwa1_'+'A'.repeat(43),spki_sha256_hex:'00'.repeat(32),host:'127.0.0.1',port:address.port};
  const saved=JSON.stringify(paired);writeFileSync(join(state,'pairing.json'),saved);
  try {
    const result=await run(['setup','--codex','--yes'],{...process.env,PATH:bin,XDG_STATE_HOME:dir,TEST_AGENT_LOG:log});
    assert.equal(result.status,0,result.stderr);
    assert.match(result.stderr,/Couldn't reach Offline phone now; keeping the pairing\./);
    assert.match(result.stderr,/--host.*unpair/);
    assert.match(result.stdout,/Agents registered: Codex/);
    assert.doesNotMatch(result.stdout,/Code shown|installing Claude Code or Codex/);
    assert.equal(readFileSync(join(state,'pairing.json'),'utf8'),saved);
    const calls=readFileSync(log,'utf8').trim().split('\n').map(line=>JSON.parse(line));
    assert.equal(calls.length,2);assert.equal(calls[1][1],'add');
    rmSync(join(bin,'codex'));
    const missing=await run(['setup','--codex','--yes'],{...process.env,PATH:bin,XDG_STATE_HOME:dir});
    assert.equal(missing.status,1);assert.match(missing.stderr,/Codex was not found on PATH/);
  } finally {rmSync(dir,{recursive:true,force:true});}
});
test('CLI old watchctl operations, USB setup, ANDROID_SERIAL, safe browser arguments and errors',async()=>{
  const dir=mkdtempSync(join(tmpdir(),'hypr-cli-')),bin=join(dir,'bin'),state=join(dir,'hypr-watch'),log=join(dir,'adb.log'),browserLog=join(dir,'browser.log');mkdirSync(bin);mkdirSync(state);
  const token='test-only-credential-that-is-at-least-32-characters';
  writeFileSync(join(bin,'adb'),`#!/usr/bin/env node
const fs=require('node:fs');fs.appendFileSync(process.env.TEST_ADB_LOG,JSON.stringify({args:process.argv.slice(2),serial:process.env.ANDROID_SERIAL})+'\\n');
if(process.argv[2]==='get-state')process.stdout.write('device');
if(process.argv[2]==='exec-out')process.stdout.write(process.env.TEST_USB_TOKEN);
`,{mode:0o755});
  const openScript=`#!/usr/bin/env node
require('node:fs').writeFileSync(process.env.TEST_BROWSER_LOG,JSON.stringify(process.argv.slice(2)));
require('node:http').get(process.argv.at(-1),r=>r.resume());
`;
  for(const name of ['open','xdg-open']) writeFileSync(join(bin,name),openScript,{mode:0o755});
  const env={...process.env,PATH:bin+':'+process.env.PATH,XDG_STATE_HOME:dir,ANDROID_SERIAL:'test-only-serial',HYPR_WATCH_TRANSPORT:'usb',TEST_ADB_LOG:log,TEST_BROWSER_LOG:browserLog,TEST_USB_TOKEN:token};
  const requests:unknown[]=[];
  const server=createServer((req,res)=>{void(async()=>{
    if(!req.url?.startsWith('/api/')) {res.writeHead(404);res.end();return;}
    const chunks:Buffer[]=[];for await(const chunk of req) chunks.push(Buffer.from(chunk));
    requests.push({path:req.url,method:req.method,data:chunks.length?JSON.parse(Buffer.concat(chunks).toString()):null,authorization:req.headers.authorization});
    res.writeHead(200,{'Content-Type':'application/json'});res.end(JSON.stringify({ok:true}));
  })();});
  server.listen(0,'127.0.0.1');await once(server,'listening');const address=server.address();assert.ok(address && typeof address!=='string');
  const resetConnection=()=>writeFileSync(join(state,'connection.json'),JSON.stringify({url:`http://127.0.0.1:${address.port}`,token}));
  try {
    const help=await run(['--help'],env);assert.equal(help.status,0);assert.match(help.stdout,/hypr-watch pair/);
    const linked=join(bin,'hypr-watch');symlinkSync(cli,linked);const linkedHelp=await run(['--help'],env,linked);assert.equal(linkedHelp.status,0);assert.equal(linkedHelp.stdout,help.stdout);
    const missing=await run(['status'],env);assert.equal(missing.status,1);assert.match(missing.stderr,/Run hypr-watch usb first/);assert.equal(existsSync(log),false);
    for(const operation of ['status','notes','connect','disconnect','reminders','sync','export']) {
      resetConnection();const result=await run([operation],env);assert.equal(result.status,0,result.stderr);assert.deepEqual(JSON.parse(result.stdout),operation==='status'?{ok:true,pairing:null}:{ok:true});
    }
    for(const args of [ ['create','--title','Example','--body','Body','--watch-title','Short','--done','false','--send-to-watch','true'],['update','--id','42','--title','Edited','--done','true'],['delete','--id','42'] ]) {
      resetConnection();const result=await run(args,env);assert.equal(result.status,0,result.stderr);
    }
    assert.deepEqual(requests,[
      {path:'/api/status',method:'GET',data:null,authorization:'Bearer '+token},
      {path:'/api/notes',method:'GET',data:null,authorization:'Bearer '+token},
      {path:'/api/watch/connect',method:'POST',data:{},authorization:'Bearer '+token},
      {path:'/api/watch/disconnect',method:'POST',data:{},authorization:'Bearer '+token},
      {path:'/api/watch/reminders',method:'GET',data:null,authorization:'Bearer '+token},
      {path:'/api/watch/sync',method:'POST',data:{},authorization:'Bearer '+token},
      {path:'/api/export',method:'GET',data:null,authorization:'Bearer '+token},
      {path:'/api/notes',method:'POST',data:{title:'Example',body:'Body',watch_title:'Short',done:false,watch_enabled:true},authorization:'Bearer '+token},
      {path:'/api/notes/42',method:'PATCH',data:{title:'Edited',done:true},authorization:'Bearer '+token},
      {path:'/api/notes/42',method:'DELETE',data:{},authorization:'Bearer '+token},
    ]);
    const paired={phone_id:'AQEBAQEBAQEBAQEBAQEBAQ',phone_name:'Test phone',agent_id:'AgICAgICAgICAgICAgICAg',token:'hwa1_'+'A'.repeat(43),spki_sha256_hex:'00'.repeat(32),host:'192.0.2.1',port:8767,last_success_at:'2026-10-01T00:00:00.000Z'};
    writeFileSync(join(state,'pairing.json'),JSON.stringify(paired));
    resetConnection();
    for(const args of [['export'],['create','--id','existing-note','--title','Edited']]) {
      const result=await run(args,{...env,HYPR_WATCH_TRANSPORT:'lan'});assert.equal(result.status,0,result.stderr);
    }
    assert.deepEqual(requests.slice(-2),[
      {path:'/api/export',method:'GET',data:null,authorization:'Bearer '+token},
      {path:'/api/notes/existing-note',method:'POST',data:{title:'Edited'},authorization:'Bearer '+token},
    ]);
    const status=await run(['status'],env);assert.equal(status.status,0,status.stderr);
    assert.deepEqual(JSON.parse(status.stdout),{ok:true,pairing:{phone_name:paired.phone_name,host:paired.host,port:paired.port,last_success_at:paired.last_success_at}});
    assert.ok(!status.stdout.includes(paired.token));
    const adbScript=readFileSync(join(bin,'adb'),'utf8');
    writeFileSync(join(bin,'adb'),'#!/usr/bin/env node\nprocess.exit(1);\n',{mode:0o755});
    for(const args of [['export'],['create','--id','existing-note']]) {
      const result=await run(args,{...env,HYPR_WATCH_TRANSPORT:'lan'});assert.equal(result.status,1);assert.match(result.stderr,/requires USB/);
    }
    writeFileSync(join(bin,'adb'),adbScript,{mode:0o755});
    await run(['unpair'],env);
    for(const args of [[],['usb'],['--open'],['usb','--open']]) {
      const result=await run(args,env);assert.equal(result.status,0,result.stderr);assert.match(result.stdout,/Access credential saved privately/);assert.ok(!result.stdout.includes(token));
      assert.equal(JSON.parse(readFileSync(join(state,'connection.json'),'utf8')).token,token);
    }
    const browserArgs=z.array(z.string()).parse(JSON.parse(readFileSync(browserLog,'utf8')));assert.ok(browserArgs.every(arg=>!arg.includes(token)));assert.match(browserArgs[0],/^http:\/\/127\.0\.0\.1:\d+\/$/);
    for(const name of ['open','xdg-open']) writeFileSync(join(bin,name),`#!/usr/bin/env node
require('node:fs').writeFileSync(process.env.TEST_BROWSER_LOG,JSON.stringify(process.argv.slice(2)));
`,{mode:0o755});
    const slowBrowser=await run(['usb','--open'],env);assert.equal(slowBrowser.status,0,slowBrowser.stderr);assert.match(slowBrowser.stdout,/Access credential saved privately/);
    const slowArgs=z.array(z.string()).parse(JSON.parse(readFileSync(browserLog,'utf8')));
    await new Promise<void>((resolve,reject)=>{get(slowArgs[0],res=>{assert.equal(res.statusCode,302);assert.equal(res.headers.location,'http://127.0.0.1:8766/#token='+token);res.resume();res.on('end',resolve);}).on('error',reject);});
    const commands=z.array(z.object({args:z.array(z.string()),serial:z.string()})).parse(readFileSync(log,'utf8').trim().split('\n').map(line=>JSON.parse(line)));
    assert.ok(commands.length>10);assert.ok(commands.every(c=>c.serial==='test-only-serial' && !JSON.stringify(c.args).includes(token)));
    writeFileSync(join(state,'pairing.json'),'{}');const removed=await run(['unpair'],env);assert.equal(removed.status,0);assert.equal(existsSync(join(state,'pairing.json')),false);
    for(const [args,message] of [ [['usb','--watch','--release'],'mutually exclusive'],[['update'],'--id is required'],[['update','--id','bad\nid'],'note_id must'],[['create','--done','yes'],'--done must be true or false'],[['create','--send-to-watch','yes'],'--send-to-watch must be true or false'],[['unknown'],'Usage:'],[['--unknown'],'Unknown option'] ] as const) {
      const result=await run([...args],env);assert.equal(result.status,1);assert.ok(result.stderr.includes(message),result.stderr);
    }
    resetConnection();writeFileSync(join(state,'companion.json'),'{"restore_mi_fitness":true}');const released=await run(['usb','--release'],env);assert.equal(released.status,0,released.stderr);assert.equal(JSON.parse(released.stdout).mi_fitness_restored,true);assert.equal(existsSync(join(state,'companion.json')),false);
  } finally {server.closeAllConnections();await new Promise<void>(resolve=>server.close(()=>resolve()));rmSync(dir,{recursive:true,force:true});}
});

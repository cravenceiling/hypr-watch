#!/usr/bin/env node
import { parseArgs } from 'node:util';
import { hostname } from 'node:os';
import { pathToFileURL } from 'node:url';
import { realpathSync } from 'node:fs';
import { createInterface } from 'node:readline/promises';
import { execFile,fork } from 'node:child_process';
import { pair } from './pairing.ts';
import { discover,parseHost } from './discovery.ts';
import { unpair,readPairing } from './state.ts';
import { call,createCall,usbCall,usbAvailable } from './watch_client.ts';
import { LanError } from './lan.ts';
import { registerAgents } from './setup.ts';
import { connectUsb,phone } from './phone.ts';
import { noteId } from './validate.ts';

const usage=`Usage: hypr-watch setup [--host ip[:port]] [--name name] [--claude] [--codex] [--yes]
       hypr-watch mcp
       hypr-watch pair [--host ip[:port]] [--name name]
       hypr-watch unpair | status
       hypr-watch usb [--watch | --release] [--open]
       hypr-watch notes | connect | disconnect | reminders | sync | export
       hypr-watch create | update | delete [--id id] [--title text] [--body text]
         [--watch-title text] [--done true|false] [--send-to-watch true|false]
`;
export async function openDashboard(token:string):Promise<void> {
  // The redirect outlives the CLI so a slow browser never blocks USB setup.
  await new Promise<void>((resolve,reject)=>{
    const redirect=fork(new URL(import.meta.url.endsWith('.ts')?'./dashboard.ts':'./dashboard.js',import.meta.url),{detached:true,stdio:['ignore','ignore','ignore','ipc']});
    const timer=setTimeout(()=>{redirect.kill();reject(new Error('Could not open the dashboard. Retry hypr-watch usb --open.'));},5000);
    redirect.once('error',error=>{clearTimeout(timer);reject(error);});
    redirect.once('exit',()=>{clearTimeout(timer);reject(new Error('Could not open the dashboard. Retry hypr-watch usb --open.'));});
    redirect.once('message',url=>{
      clearTimeout(timer);
      redirect.removeAllListeners('exit');
      if(typeof url!=='string') {redirect.kill();reject(new Error('Invalid dashboard address.'));return;}
      redirect.unref();
      const command=process.platform==='darwin'?'open':process.platform==='win32'?'rundll32':'xdg-open';
      const args=process.platform==='win32'?['url.dll,FileProtocolHandler',url]:[url];
      execFile(command,args,{timeout:10000},error=>{if(error) {redirect.kill();reject(new Error('Could not open the browser. Retry hypr-watch usb --open.'));} else resolve();});
    });
    redirect.send(token);
  });
}
export async function main(args=process.argv.slice(2)):Promise<void> {
  const {values,positionals}=parseArgs({args,allowPositionals:true,options:{
    host:{type:'string'},name:{type:'string'},claude:{type:'boolean'},codex:{type:'boolean'},yes:{type:'boolean'},open:{type:'boolean'},watch:{type:'boolean'},release:{type:'boolean'},
    id:{type:'string'},title:{type:'string'},body:{type:'string'},'watch-title':{type:'string'},done:{type:'string'},'send-to-watch':{type:'string'},help:{type:'boolean',short:'h'},
  }});
  if(values.help) {process.stdout.write(usage);return;}
  if(positionals.length>1) throw new Error(usage);
  const command=positionals[0]??'usb';
  if(command==='setup' && process.platform==='win32') throw new Error('Hypr Watch setup supports macOS and Linux. Windows is not supported.');
  const print=(result:unknown)=>process.stdout.write(JSON.stringify(result,null,2)+'\n');
  if(command==='mcp') {
    const {createServer}=await import('./main.ts');
    const {StdioServerTransport}=await import('@modelcontextprotocol/sdk/server/stdio.js');
    await createServer().connect(new StdioServerTransport());return;
  }
  if(command==='pair' || command==='setup') {
    let paired=command==='setup'?readPairing():null;
    let repair=false;
    if(paired) {
      try {
        await createCall({mode:()=>'lan',pairing:()=>paired && values.host?{...paired,...parseHost(values.host)}:paired})('status');
        process.stdout.write(`Already paired with ${paired.phone_name}. Wi-Fi connection works.\n`);
      } catch(error) {
        if(error instanceof LanError && error.kind==='connect') {
          process.stderr.write(`Couldn't reach ${paired.phone_name} now; keeping the pairing.\nRetry setup with --host ip[:port], or run npx hypr-watch unpair to remove it.\n`);
        } else {
          if(!(error instanceof LanError) || (error.kind!=='pin' && error.kind!=='revoked')) throw error;
          process.stdout.write(error.kind==='pin'?"The phone's identity changed. Open Settings > Agents > Connect an agent to pair again.\n":'This computer was revoked on the phone. Open Settings > Agents > Connect an agent to pair again.\n');
          paired=null;repair=true;
        }
      }
    }
    let endpoint;
    // Discover before opening readline: piped input would reach EOF and close it during the browse.
    // One entry per phone: a phone answers on several addresses, best first.
    let candidates=paired || values.host || repair?[]:(await discover()).filter((c,i,all)=>c.pairing && all.findIndex(o=>o.phone_id===c.phone_id)===i);
    const terminal=createInterface({input:process.stdin,output:process.stdout});
    // Buffer piped answers while discovery, TLS pairing, and agent commands run.
    const lines=terminal[Symbol.asyncIterator]();
    const question=async(prompt:string)=>{
      process.stdout.write(prompt);
      const answer=await lines.next();
      if(answer.done) throw new Error('Input ended. Run hypr-watch '+command+' in a terminal.');
      return answer.value;
    };
    try {
      if(!paired) {
        if(repair) {
          await question('Press Enter when Connect an agent is open on the phone. ');
          if(!values.host) candidates=(await discover()).filter((c,i,all)=>c.pairing && all.findIndex(o=>o.phone_id===c.phone_id)===i);
        }
        if(values.host) endpoint=parseHost(values.host);
        else {
          if(!candidates.length) throw new Error('No phone is ready to pair. Open Settings > Agents > Connect an agent, or use --host ip[:port].');
          if(candidates.length===1) endpoint=candidates[0];
          else {
            candidates.forEach((c,i)=>process.stdout.write(`${i+1}. ${c.name} (${c.host}:${c.port})\n`));
            const selected=Number(await question('Choose a phone: '));
            if(!Number.isInteger(selected) || selected<1 || selected>candidates.length) throw new Error('Choose one of the listed phones.');
            endpoint=candidates[selected-1];
          }
        }
        const code=await question('Code shown on the phone: ');
        paired=await pair({host:endpoint.host,port:endpoint.port},code,values.name??hostname());
        process.stdout.write(`Paired with ${paired.phone_name}.\n`);
      }
      if(command==='setup') {
        const registered=await registerAgents(values,question);
        process.stdout.write(`Phone: ${paired.phone_name}. Agents registered: ${registered.join(', ') || 'none'}.\n`);
        if(registered.length) process.stdout.write(`Restart ${registered.join(' and ')}, then ask: how did I sleep this week?\n`);
      }
    } finally {terminal.close();}
    return;
  }
  if(command==='unpair') {unpair();process.stdout.write('Pairing removed from this computer. Revoke it on the phone to invalidate its token.\n');return;}
  if(command==='usb') {
    if(values.watch && values.release) throw new Error('--watch and --release are mutually exclusive.');
    if(values.release) {print(await phone.release());return;}
    const token=connectUsb();if(values.watch) print(await phone.takeOver());
    if(values.open) await openDashboard(token);
    process.stdout.write('PC notes: http://127.0.0.1:8766. Access credential saved privately.\n');return;
  }
  if(command==='status') {
    const result=await call('status'),paired=readPairing();
    print({...result,pairing:paired?{phone_name:paired.phone_name,host:paired.host,port:paired.port,last_success_at:paired.last_success_at??null}:null});return;
  }
  if(command==='export' && !usbAvailable()) throw new Error('Export requires USB. Connect the phone with debugging authorized, then run hypr-watch usb.');
  const simple:Record<string,[string,string]>={notes:['notes','GET'],export:['export','GET'],connect:['watch/connect','POST'],disconnect:['watch/disconnect','POST'],reminders:['watch/reminders','GET'],sync:['watch/sync','POST']};
  if(Object.hasOwn(simple,command)) {const [path,method]=simple[command];print(await (command==='export'?usbCall:call)(path,method,method==='POST'?{}:undefined));return;}
  if(!['create','update','delete'].includes(command)) throw new Error(usage);
  if(command!=='create' && !values.id) throw new Error('--id is required');
  if(values.id!==undefined) noteId.parse(values.id);
  if(command==='create' && values.id!==undefined && !usbAvailable()) throw new Error('Creating a note with --id requires USB. Connect the phone with debugging authorized, then run hypr-watch usb.');
  const data:Record<string,unknown>={};
  for(const [key,value] of [['title',values.title],['body',values.body],['watch_title',values['watch-title']]] as const) if(value!==undefined) data[key]=value;
  for(const [key,value] of [['done',values.done],['watch_enabled',values['send-to-watch']]] as const) {
    if(value===undefined) continue;
    if(value!=='true' && value!=='false') throw new Error(`${key==='done'?'--done':'--send-to-watch'} must be true or false`);
    data[key]=value==='true';
  }
  print(await (command==='create' && values.id!==undefined?usbCall:call)('notes'+(values.id?'/'+values.id:''),command==='create'?'POST':command==='update'?'PATCH':'DELETE',data));
}
if(process.argv[1] && import.meta.url===pathToFileURL(realpathSync(process.argv[1])).href) {
  try {await main();} catch(error) {process.stderr.write((error instanceof Error?error.message:String(error))+'\n');process.exitCode=1;}
}

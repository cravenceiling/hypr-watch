import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync,writeFileSync,readFileSync,existsSync,rmSync,mkdirSync,statSync,readdirSync,unlinkSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join,resolve } from 'node:path';
import { spawnSync } from 'node:child_process';
import { createPhone,RELEASED_PREFS,STOPPED } from '../server/phone.ts';
import type { PhoneDependencies } from '../server/phone.ts';
import { stateDir,connection } from '../server/state.ts';
import { LanError,IDENTITY_ERROR,REVOKED_ERROR } from '../server/lan.ts';

const ENABLE=['shell','pm','enable','--user','0','com.xiaomi.wearable'];
const STOP=['shell','am','force-stop','local.hyprwatch'];
const WRITE=['shell',"run-as local.hyprwatch sh -c 'mkdir -p shared_prefs && cat > shared_prefs/watch-link.xml.new && rm -f shared_prefs/watch-link.xml.bak && mv shared_prefs/watch-link.xml.new shared_prefs/watch-link.xml'"];
const READ=['exec-out','run-as','local.hyprwatch','cat','shared_prefs/watch-link.xml'];
const matches=(a:unknown,b:unknown)=>JSON.stringify(a)===JSON.stringify(b);
function fixture(overrides:Partial<PhoneDependencies>={}) {
  const dir=mkdtempSync(join(tmpdir(),'hypr-companion-')),calls:unknown[][]=[],written:string[]=[];
  const deps:PhoneDependencies={
    stateDir:()=>dir,connectUsb:()=>'',usbAvailable:()=>true,sleep:async()=>{},now:()=>0,
    adb:(args,input)=>{calls.push(['adb',...args]);if(input!==undefined) written.push(input);return matches(args,READ)?(written.at(-1)??RELEASED_PREFS).trim():'';},
    call:async(path)=>{calls.push(['api',path]);return {watch:{state:'disconnected'}};},...overrides,
  };
  return {dir,calls,written,deps,phone:createPhone(deps),record:join(dir,'companion.json'),close:()=>rmSync(dir,{recursive:true,force:true})};
}
for(const failure of [ ['shell','am','force-stop','nodomain.freeyourgadget.gadgetbridge'],['shell','pm','disable-user','--user','0','com.xiaomi.wearable'] ]) {
  test('failed take-over restores companion after '+failure.join(' '),async()=>{
    const f=fixture();const adb=f.deps.adb;
    const phone=createPhone({...f.deps,adb:(args,input)=>{if(matches(args,failure)) throw new Error('Simulated ADB failure');return adb(args,input);}});
    try {await assert.rejects(phone.takeOver(),/Simulated ADB failure/);assert.ok(f.calls.some(c=>matches(c,['adb',...ENABLE])));assert.equal(existsSync(f.record),false);}
    finally {f.close();}
  });
}
test('failed connect releases the watch before restoring Mi Fitness',async()=>{
  const f=fixture();const phone=createPhone({...f.deps,call:async path=>{f.calls.push(['api',path]);return {watch:{state:'error',error:'Timed out at opening socket'}};}});
  try {
    await assert.rejects(phone.takeOver(),/Timed out/);
    const enable=f.calls.findIndex(c=>matches(c,['adb',...ENABLE]));assert.ok(enable>0);assert.ok(f.calls.slice(0,enable).some(c=>matches(c,['api','watch/disconnect'])));
  } finally {f.close();}
});
test('unavailable status never disables a companion',async()=>{
  const f=fixture({call:async()=>{throw new Error('Unavailable');}});
  try {await assert.rejects(f.phone.takeOver(),/Unavailable/);assert.deepEqual(f.calls,[]);assert.equal(existsSync(f.record),false);}
  finally {f.close();}
});
for(const states of [['connecting','connecting','connected'],['connecting','error'],['connected']]) {
  test('take-over waits for '+states.join(', '),async()=>{
    const f=fixture(),pending=[...states];
    const phone=createPhone({...f.deps,call:async path=>{f.calls.push(['api',path]);return {watch:{state:path==='status'?(pending.length>1?pending.shift():pending[0]):'connected'}};}});
    try {
      const result=await phone.takeOver();
      if(states.at(-1)==='connected') {assert.equal(result.message,'Hypr Watch already has the watch connection.');assert.equal(f.calls.length,states.length);assert.ok(f.calls.every(c=>c[0]==='api'));}
      else {assert.equal(result.mi_fitness_paused,true);assert.deepEqual(f.calls.filter(c=>c[0]==='api'),[['api','status'],['api','status'],['api','watch/connect']]);}
    } finally {f.close();}
  });
}
test('take-over leaves a connection that never settles alone',async()=>{
  let clock=0;const f=fixture({now:()=>{clock+=10000;return clock;},call:async()=>({watch:{state:'connecting'}})});
  try {await assert.rejects(f.phone.takeOver(),/still connecting/);assert.deepEqual(f.calls,[]);}
  finally {f.close();}
});
for(const owned of [true,false]) {
  test(`failed rerun with auto_reconnect ${owned} respects ownership`,async()=>{
    const f=fixture();writeFileSync(f.record,JSON.stringify({restore_mi_fitness:true}));
    const phone=createPhone({...f.deps,call:async path=>{f.calls.push(['api',path]);return {watch:{state:'error',auto_reconnect:owned,error:'Timed out at opening socket'}};}});
    try {
      await assert.rejects(phone.takeOver(),{message:owned?'Timed out at opening socket. Hypr Watch still owns the watch and keeps reconnecting, so Mi Fitness stays off. Run hypr-watch usb --release to give it back.':'Timed out at opening socket'});
      assert.equal(existsSync(f.record),owned);assert.equal(f.calls.some(c=>matches(c,['adb',...ENABLE])),!owned);assert.equal(f.calls.some(c=>matches(c,['api','watch/disconnect'])),!owned);
    } finally {f.close();}
  });
}
test('failed take-over names the ADB release fallback',async()=>{
  const f=fixture({call:async path=>{if(path==='watch/disconnect') throw new Error('Hypr Watch is not answering');return {watch:{state:'error',error:'Timed out at opening socket'}};}});
  try {await assert.rejects(f.phone.takeOver(),{message:'Timed out at opening socket. '+STOPPED+' Mi Fitness is back.'});assert.equal(existsSync(f.record),false);}
  finally {f.close();}
});
test('API release restores Mi Fitness',async()=>{
  const f=fixture();writeFileSync(f.record,JSON.stringify({restore_mi_fitness:true}));
  try {assert.deepEqual(await f.phone.release(),{watch:{state:'disconnected'},mi_fitness_restored:true});assert.deepEqual(f.calls,[['api','watch/disconnect'],['adb',...ENABLE]]);assert.equal(existsSync(f.record),false);}
  finally {f.close();}
});
for(const apiError of [new Error('Hypr Watch is not answering'),new Error('watch')]) {
  test('failed API release durably writes released prefs before enabling Mi Fitness '+apiError.message,async()=>{
    const f=fixture();writeFileSync(f.record,JSON.stringify({restore_mi_fitness:true}));
    const phone=createPhone({...f.deps,call:async path=>{f.calls.push(['api',path]);throw apiError;}});
    try {
      assert.deepEqual(await phone.release(),{watch:{state:'disconnected'},mi_fitness_restored:true,message:STOPPED});
      assert.deepEqual(f.calls,[['api','watch/disconnect'],['adb',...STOP],['adb',...WRITE],['adb',...READ],['adb',...ENABLE]]);
      assert.deepEqual(f.written,[RELEASED_PREFS]);assert.equal(existsSync(f.record),false);
      assert.deepEqual([...RELEASED_PREFS.matchAll(/<boolean name="(\w+)" value="(\w+)"/g)].map(m=>[m[1],m[2]]),[['owned','false'],['released','true']]);
      const source=readFileSync(new URL('../../android/src/local/hyprwatch/NotesService.kt',import.meta.url),'utf8');
      const store=source.slice(source.indexOf('class LinkStore'),source.indexOf('class LinkStartReceiver'));
      assert.match(store,/private const val FILE = "watch-link"/);
      for(const operation of ['getBoolean','putBoolean']) assert.deepEqual(new Set([...store.matchAll(new RegExp(operation+'\\("(\\w+)"','g'))].map(m=>m[1])),new Set(['owned','released']));
    } finally {f.close();}
  });
}
for(const failure of [STOP,WRITE,READ,['bad read']]) {
  test('Mi Fitness stays paused when release fails at '+failure.join(' '),async()=>{
    const f=fixture();writeFileSync(f.record,JSON.stringify({restore_mi_fitness:true}));const adb=f.deps.adb;
    const phone=createPhone({...f.deps,call:async()=>{throw new Error('Hypr Watch is not answering');},adb:(args,input)=>{if(matches(args,failure)) throw new Error('ADB failure');if(matches(failure,['bad read']) && matches(args,READ)) return '<map />';return adb(args,input);}});
    try {await assert.rejects(phone.release(),/Mi Fitness stays paused/);assert.ok(existsSync(f.record));assert.ok(!f.calls.some(c=>matches(c,['adb',...ENABLE])));}
    finally {f.close();}
  });
}
test('failed take-over keeps Mi Fitness paused if release cannot be guaranteed',async()=>{
  const f=fixture();const adb=f.deps.adb;
  const phone=createPhone({...f.deps,call:async path=>{if(path==='watch/disconnect') throw new Error('Hypr Watch is not answering');return {watch:{state:'error',error:'Timed out at opening socket'}};},adb:(args,input)=>{if(matches(args,STOP)) throw new Error('ADB failure');return adb(args,input);}});
  try {await assert.rejects(phone.takeOver(),/^Error: Timed out at opening socket\. Hypr Watch did not release the watch .*Mi Fitness stays paused\. Reconnect the phone by USB, then run hypr-watch usb --release\.$/);assert.ok(existsSync(f.record));assert.ok(!f.calls.some(c=>matches(c,['adb',...ENABLE])));}
  finally {f.close();}
});
test('LAN-only handoff calls API without ADB and retains the companion record',async()=>{
  const f=fixture({usbAvailable:()=>false});
  f.phone=createPhone({...f.deps,call:async path=>{f.calls.push(['api',path]);return {watch:{state:path==='watch/connect'?'connected':'disconnected'}};}});
  writeFileSync(f.record,JSON.stringify({restore_mi_fitness:true}));
  try {
    assert.equal((await f.phone.takeOver()).mi_fitness_paused,false);assert.equal((await f.phone.release()).mi_fitness_restored,false);
    assert.deepEqual(f.calls,[['api','status'],['api','watch/connect'],['api','watch/disconnect']]);assert.ok(existsSync(f.record));
  } finally {f.close();}
});
for(const states of [['connected'],['connecting','connected'],['connecting','error']]) {
  test('LAN handoff waits and checks '+states.join(', '),async()=>{
    const f=fixture({usbAvailable:()=>false}),pending=[...states];
    const phone=createPhone({...f.deps,call:async path=>{f.calls.push(['api',path]);return {watch:{state:path==='status'?(pending.length>1?pending.shift():pending[0]):'connected'}};}});
    try {
      const result=await phone.takeOver();
      assert.equal(result.mi_fitness_paused,false);assert.match(String(result.message),/Mi Fitness was not paused/);
      assert.deepEqual(f.calls,states.map(()=>['api','status']).concat(states.at(-1)==='error'?[['api','watch/connect']]:[]));
      assert.equal(existsSync(f.record),false);
    } finally {f.close();}
  });
}
test('LAN handoff checks failed final state without changing the companion record',async()=>{
  const f=fixture({usbAvailable:()=>false,call:async path=>({watch:{state:path==='status'?'disconnected':'error',error:'Bluetooth unavailable'}})});
  writeFileSync(f.record,JSON.stringify({restore_mi_fitness:true}));
  try {await assert.rejects(f.phone.takeOver(),{message:'Bluetooth unavailable'});assert.deepEqual(f.calls,[]);assert.ok(existsSync(f.record));}
  finally {f.close();}
});
test('LAN handoff times out a connect in progress without restarting it',async()=>{
  let clock=0;
  const f=fixture({usbAvailable:()=>false,now:()=>clock, sleep:async ms=>{clock+=ms;},call:async path=>{assert.equal(path,'status');return {watch:{state:'connecting'}};}});
  try {await assert.rejects(f.phone.takeOver(),/still connecting/);assert.equal(clock,35000);assert.deepEqual(f.calls,[]);}
  finally {f.close();}
});
for(const [message,kind] of [[IDENTITY_ERROR,'pin'],[REVOKED_ERROR,'revoked']] as const) {
  test('identity and revocation failures never trigger companion fallback '+kind,async()=>{
    let requests=0;
    const f=fixture({call:async()=>{requests++;throw new LanError(message,kind,false);}});
    writeFileSync(f.record,JSON.stringify({restore_mi_fitness:true}));
    try {
      await assert.rejects(f.phone.takeOver(),{message});assert.equal(requests,1);assert.deepEqual(f.calls,[]);
      await assert.rejects(f.phone.release(),{message});assert.equal(requests,2);assert.deepEqual(f.calls,[]);assert.ok(existsSync(f.record));
    } finally {f.close();}
  });
}
test('legacy connection and restore records migrate once with private permissions, leaving signing keys alone',()=>{
  const dir=mkdtempSync(join(tmpdir(),'hypr-state-')),prior=process.env.XDG_STATE_HOME;process.env.XDG_STATE_HOME=dir;
  try {
    const old=join(dir,'redmi-notes'),target=join(dir,'hypr-watch');mkdirSync(old);mkdirSync(target);
    writeFileSync(join(old,'companion.json'),'{"restore_mi_fitness":true}');writeFileSync(join(old,'connection.json'),'{"url":"http://127.0.0.1:8766","token":"old"}');writeFileSync(join(old,'signing.keystore'),'test-only');
    writeFileSync(join(target,'connection.json'),'{"url":"http://127.0.0.1:8766","token":"new"}');
    assert.equal(stateDir(),target);assert.equal(connection().token,'new');assert.equal(statSync(target).mode&0o777,0o700);assert.equal(statSync(join(target,'companion.json')).mode&0o777,0o600);
    assert.equal(existsSync(join(old,'companion.json')),false);assert.equal(existsSync(join(old,'connection.json')),true);assert.equal(existsSync(join(old,'signing.keystore')),true);assert.equal(existsSync(join(target,'signing.keystore')),false);
    unlinkSync(join(target,'companion.json'));stateDir();assert.equal(existsSync(join(target,'companion.json')),false);
    assert.equal(createPhone({stateDir:()=>target}).restoreCompanion(),false);
    unlinkSync(join(target,'connection.json'));assert.equal(connection().token,'old');assert.equal(existsSync(join(old,'connection.json')),false);
    writeFileSync(join(old,'companion.json'),'{"restore_mi_fitness":true}');writeFileSync(join(target,'companion.json'),'{"restore_mi_fitness":false}');stateDir();
    const calls:string[][]=[];assert.equal(createPhone({stateDir:()=>target,adb:args=>{calls.push(args);return '';}}).restoreCompanion(),true);assert.deepEqual(calls,[ENABLE]);assert.equal(existsSync(join(old,'companion.json')),false);
  } finally {if(prior===undefined) delete process.env.XDG_STATE_HOME;else process.env.XDG_STATE_HOME=prior;rmSync(dir,{recursive:true,force:true});}
});
for(const scenario of ['missing','old','argument']) {
  test('Android build key check '+scenario,()=>{
    const dir=mkdtempSync(join(tmpdir(),'hypr-build-key-')),old=join(dir,'redmi-notes');
    try {
      if(scenario==='old') {mkdirSync(old);writeFileSync(join(old,'signing.keystore'),'test-only');}
      const result=spawnSync('bash',[resolve('../scripts/build-android.sh'),...(scenario==='argument'?['--new']:[])],{env:{...process.env,XDG_STATE_HOME:dir},encoding:'utf8',timeout:30000});
      assert.equal(result.status,scenario==='argument'?2:1);
      if(scenario==='argument') assert.match(result.stderr,/\[--new-key\]/);
      else {
        assert.ok(result.stderr.includes(`No signing key at ${dir}/hypr-watch/signing.keystore.`));assert.match(result.stderr,/run again with --new-key/);assert.equal(result.stdout,'');
        if(scenario==='missing') {assert.doesNotMatch(result.stderr,/redmi-notes/);assert.deepEqual(readdirSync(dir),[]);}
        else {assert.ok(result.stderr.includes(`The key from before the rename is at ${old}/signing.keystore.`));assert.ok(result.stderr.includes(`mv ${old}/signing.keystore ${dir}/hypr-watch/signing.keystore`));assert.equal(existsSync(join(dir,'hypr-watch')),false);assert.equal(readFileSync(join(old,'signing.keystore'),'utf8'),'test-only');}
      }
    } finally {rmSync(dir,{recursive:true,force:true});}
  });
}

import { readFileSync, existsSync, unlinkSync } from 'node:fs';
import { join } from 'node:path';
import { setTimeout as sleep } from 'node:timers/promises';
import { z } from 'zod';
import { call, adb, connectUsb, usbAvailable, APP } from './watch_client.ts';
export { adb, connectUsb, usbAvailable, APP } from './watch_client.ts';
import type { ApiCall } from './watch_client.ts';
import { stateDir, atomicWrite } from './state.ts';
import { LanError } from './lan.ts';

export const RELEASED_PREFS=`<?xml version='1.0' encoding='utf-8' standalone='yes' ?>
<map>
    <boolean name="owned" value="false" />
    <boolean name="released" value="true" />
</map>
`;
export const STOPPED='Hypr Watch did not answer, so it was stopped and its watch marked released over ADB. Open it again to use notes; it will not reconnect the watch.';
const watchSchema=z.object({state:z.string(),auto_reconnect:z.boolean().optional(),error:z.string().nullable().optional()}).passthrough();
const companionSchema=z.object({restore_mi_fitness:z.boolean().optional()});
export type PhoneDependencies={call:ApiCall;adb:typeof adb;stateDir:()=>string;connectUsb:()=>string;usbAvailable:()=>boolean;sleep:(ms:number)=>Promise<unknown>;now:()=>number};
export function createPhone(overrides:Partial<PhoneDependencies>={}) {
  const deps={call,adb,stateDir,connectUsb,usbAvailable,sleep,now:()=>performance.now(),...overrides};
  const record=()=>join(deps.stateDir(),'companion.json');
  async function watchStatus() {
    for(let attempt=0;;attempt++) {
      try {return watchSchema.parse((await deps.call('status','GET',undefined,3)).watch);}
      catch(error) {if(error instanceof LanError && (error.kind==='pin' || error.kind==='revoked') || attempt===9) throw error;await deps.sleep(300);}
    }
  }
  function restoreCompanion():boolean {
    const path=record();if(!existsSync(path)) return false;
    const restore=companionSchema.parse(JSON.parse(readFileSync(path,'utf8'))).restore_mi_fitness??false;
    if(restore) deps.adb(['shell','pm','enable','--user','0','com.xiaomi.wearable']);
    unlinkSync(path);return restore;
  }
  async function releaseApp(then='retry'):Promise<'api'|'adb'> {
    let reason:string;
    try {await deps.call('watch/disconnect','POST',{});return 'api';}
    catch(error) {
      if(error instanceof LanError && (error.kind==='pin' || error.kind==='revoked')) throw error;
      reason=error instanceof Error?error.message:String(error);
    }
    try {
      deps.adb(['shell','am','force-stop',APP]);
      const prefs='shared_prefs/watch-link.xml';
      deps.adb(['shell',`run-as ${APP} sh -c 'mkdir -p shared_prefs && cat > ${prefs}.new && rm -f ${prefs}.bak && mv ${prefs}.new ${prefs}'`],RELEASED_PREFS);
      if(deps.adb(['exec-out','run-as',APP,'cat',prefs])!==RELEASED_PREFS.trim()) throw new Error('The release did not stick.');
    } catch {throw new Error(`Hypr Watch did not release the watch (${reason}) and could not be stopped and marked released over ADB, so Mi Fitness stays paused. Reconnect the phone by USB, then ${then}.`);}
    return 'adb';
  }
  async function takeOver():Promise<Record<string,unknown>> {
    const usb=deps.usbAvailable();
    if(usb) deps.connectUsb();
    let watch=await watchStatus();const deadline=deps.now()+35000;
    while(watch.state==='connecting') {
      if(deps.now()>=deadline) throw new Error('Hypr Watch is still connecting to the watch. Check its status, then retry.');
      await deps.sleep(500);watch=await watchStatus();
    }
    if(watch.state==='connected') return {watch,...(usb?{}:{mi_fitness_paused:false}),message:'Hypr Watch already has the watch connection.'+(usb?'':' Mi Fitness was not paused.')};
    if(!usb) {
      const result=await deps.call('watch/connect','POST',{}),connected=watchSchema.parse(result.watch);
      if(connected.state!=='connected') throw new Error(connected.error || 'Could not connect to the watch');
      return {...result,mi_fitness_paused:false,message:'Mi Fitness was not paused. Connect by USB to pause it, or stop it in Android settings.'};
    }
    const owned=Boolean(watch.auto_reconnect),path=record();
    if(!existsSync(path)) {
      const disabled=deps.adb(['shell','pm','list','packages','-d','com.xiaomi.wearable']);
      atomicWrite(path,{restore_mi_fitness:!disabled.includes('package:com.xiaomi.wearable')});
    }
    try {
      deps.adb(['shell','pm','disable-user','--user','0','com.xiaomi.wearable']);
      deps.adb(['shell','am','force-stop','nodomain.freeyourgadget.gadgetbridge']);
      const result=await deps.call('watch/connect','POST',{}),connected=watchSchema.parse(result.watch);
      if(connected.state!=='connected') throw new Error(connected.error || 'Could not connect to the watch');
      return {...result,mi_fitness_paused:true,message:'Hypr Watch has the watch connection. Use disconnect_watch or hypr-watch usb --release to return it to Mi Fitness.'};
    } catch(error) {
      const reason=(error instanceof Error?error.message:String(error)).replace(/[. ]+$/,'')+'.';
      if(owned) throw new Error(`${reason} Hypr Watch still owns the watch and keeps reconnecting, so Mi Fitness stays off. Run hypr-watch usb --release to give it back.`);
      let restore=false;
      try {restore=existsSync(path) && Boolean(companionSchema.parse(JSON.parse(readFileSync(path,'utf8'))).restore_mi_fitness);} catch {}
      let how='api';
      try {if(restore) how=await releaseApp('run hypr-watch usb --release');}
      catch(releaseError) {throw new Error(`${reason} ${releaseError instanceof Error?releaseError.message:String(releaseError)}`);}
      try {restoreCompanion();}
      catch {throw new Error(`${reason} Mi Fitness could not be turned back on. Reconnect the phone by USB, then run hypr-watch usb --release.`);}
      if(how==='adb') throw new Error(`${reason} ${STOPPED} Mi Fitness is back.`);
      throw error;
    }
  }
  async function release():Promise<Record<string,unknown>> {
    if(!deps.usbAvailable()) {
      const result=await deps.call('watch/disconnect','POST',{});
      return {...result,mi_fitness_restored:false,mi_fitness_paused:false,message:'Mi Fitness was not paused or restored over Wi-Fi. Run hypr-watch usb --release with USB connected to restore its previous state.'};
    }
    const how=await releaseApp(),restored=restoreCompanion();
    return {watch:{state:'disconnected'},mi_fitness_restored:restored,...(how==='adb'?{message:STOPPED}:{})};
  }
  return {takeOver,release,releaseApp,restoreCompanion};
}
export const phone=createPhone();

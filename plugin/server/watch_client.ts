import { execFileSync } from 'node:child_process';
import { join } from 'node:path';
import { z } from 'zod';
import { connection, readPairing, savePairing, stateDir, atomicWrite } from './state.ts';
import type { Pairing } from './state.ts';
import { discover } from './discovery.ts';
import type { Candidate } from './discovery.ts';
import { LanError, lanRequest } from './lan.ts';
import { noteId } from './validate.ts';
export type ApiCall=(path:string,method?:string,data?:unknown,timeout?:number)=>Promise<Record<string,unknown>>;
export { stateDir, connection } from './state.ts';

export const APP='local.hyprwatch';
export function adb(args:string[],input?:string):string {
  try { return execFileSync('adb',args,{input:input??'',stdio:['pipe','pipe','pipe'],encoding:'utf8',timeout:12000}).trim(); }
  catch { throw new Error('Connect the phone by USB with debugging authorized, then retry.'); }
}
export function usbAvailable():boolean {try {return adb(['get-state'])==='device';} catch {return false;}}
export function connectUsb():string {
  adb(['forward','tcp:8766','tcp:8766']);
  const token=adb(['exec-out','run-as',APP,'cat','files/api-token']);
  if (token.length<32) throw new Error('Open Hypr Watch on the phone first.');
  atomicWrite(join(stateDir(),'connection.json'),{url:'http://127.0.0.1:8766',token});return token;
}
export const usbCall:ApiCall=async(path,method='GET',data,timeout=40)=>{
  const cfg=connection();
  try { execFileSync('adb',['forward','tcp:8766','tcp:8766'],{stdio:'pipe',timeout:5000}); } catch {}
  let response:Response;
  try { response=await fetch(cfg.url+'/api/'+path,{method,body:data==null?undefined:JSON.stringify(data),headers:{Authorization:'Bearer '+cfg.token,'Content-Type':'application/json'},signal:AbortSignal.timeout(timeout*1000),redirect:'error'}); }
  catch { throw new Error('Phone API unavailable. Open Hypr Watch and run hypr-watch usb again.'); }
  const payload=z.record(z.string(),z.unknown()).parse(await response.json());
  if (!response.ok) throw new Error(typeof payload.error==='string'?payload.error:`HTTP ${response.status}`);
  return payload;
};
export type TransportDependencies={pairing:()=>Pairing|null;save:(pairing:Pairing)=>void;discover:()=>Promise<Candidate[]>;lan:typeof lanRequest;usb:ApiCall;usbAvailable:()=>boolean;mode:()=>string};
export function createCall(overrides:Partial<TransportDependencies>={}):ApiCall {
  const deps={pairing:readPairing,save:savePairing,discover,lan:lanRequest,usb:usbCall,usbAvailable,mode:()=>process.env.HYPR_WATCH_TRANSPORT??'auto',...overrides};
  return async(path,method='GET',data,timeout=40)=>{
    if(path.startsWith('notes/')) noteId.parse(path.slice('notes/'.length));
    const mode=deps.mode();
    if (!['auto','lan','usb'].includes(mode)) throw new Error('HYPR_WATCH_TRANSPORT must be auto, lan, or usb.');
    if (mode==='usb') return deps.usb(path,method,data,timeout);
    const paired=deps.pairing();
    if (!paired) { if (mode==='lan') throw new Error('No phone is paired. Run `npx hypr-watch setup` first.');return deps.usb(path,method,data,timeout); }
    const request=async(record:Pairing)=> {
      const result=await deps.lan(record,{path:'/api/'+path,method,data,timeout,pin:paired.spki_sha256_hex,token:paired.token});
      deps.save({...record,last_success_at:new Date().toISOString()});return result.body;
    };
    try { return await request(paired); }
    catch(error) {
      if (!(error instanceof LanError) || error.kind!=='connect' || error.requestWritten) throw error;
      let candidates:Candidate[];
      try { candidates=await deps.discover(); } catch { candidates=[]; }
      for(const found of candidates.filter(c=>c.phone_id===paired.phone_id)) {
        const updated={...paired,host:found.host,port:found.port};
        try { return await request(updated); }
        catch(retry) { if (!(retry instanceof LanError) || retry.kind!=='connect' || retry.requestWritten) throw retry; }
      }
      if(mode==='lan' || !deps.usbAvailable()) throw new LanError(`The paired phone ${paired.phone_name} is unreachable on Wi-Fi. Check that it is on the same network and Allow agents on Wi-Fi is enabled.`,'connect',false);
      return deps.usb(path,method,data,timeout);
    }
  };
}
export const call=createCall();

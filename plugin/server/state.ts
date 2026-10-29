import { mkdirSync, chmodSync, existsSync, readFileSync, writeFileSync, renameSync, unlinkSync } from 'node:fs';
import { join } from 'node:path';
import { homedir } from 'node:os';
import { randomBytes } from 'node:crypto';
import { z } from 'zod';

export function stateDir(): string {
  const base=process.env.XDG_STATE_HOME??join(homedir(),'.local/state'),dir=join(base,'hypr-watch');
  mkdirSync(dir,{recursive:true,mode:0o700});chmodSync(dir,0o700);
  for (const name of ['connection.json','companion.json']) {
    const old=join(base,'redmi-notes',name),target=join(dir,name);
    if (!existsSync(old)) continue;
    if (existsSync(target) && name==='companion.json') {
      const current=z.object({restore_mi_fitness:z.boolean().optional()}).passthrough().parse(JSON.parse(readFileSync(target,'utf8')));
      const prior=z.object({restore_mi_fitness:z.boolean().optional()}).parse(JSON.parse(readFileSync(old,'utf8')));
      atomicWrite(target,{...current,restore_mi_fitness:Boolean(current.restore_mi_fitness || prior.restore_mi_fitness)});unlinkSync(old);
    } else if (!existsSync(target)) renameSync(old,target);
    chmodSync(target,0o600);
  }
  return dir;
}
export function atomicWrite(path:string,value:unknown):void {
  const temp=path+'.'+randomBytes(8).toString('hex')+'.tmp';
  try { writeFileSync(temp,JSON.stringify(value)+'\n',{mode:0o600,flag:'wx'});renameSync(temp,path); }
  finally { if (existsSync(temp)) unlinkSync(temp); }
}
export const pairingSchema=z.object({
  phone_id:z.string().length(22).regex(/^[A-Za-z0-9_-]{22}$/),phone_name:z.string(),spki_sha256_hex:z.string().length(64).regex(/^[0-9a-f]{64}$/),
  agent_id:z.string().length(22).regex(/^[A-Za-z0-9_-]{22}$/),token:z.string().length(48).regex(/^hwa1_[A-Za-z0-9_-]{43}$/),host:z.string().min(1),port:z.number().int().min(1).max(65535),
  last_success_at:z.iso.datetime().optional(),
});
export type Pairing=z.infer<typeof pairingSchema>;
export function readPairing():Pairing|null {
  const path=join(stateDir(),'pairing.json');
  return existsSync(path)?pairingSchema.parse(JSON.parse(readFileSync(path,'utf8'))):null;
}
export function savePairing(value:Pairing):void { atomicWrite(join(stateDir(),'pairing.json'),pairingSchema.parse(value)); }
export function unpair():void { const path=join(stateDir(),'pairing.json');if (existsSync(path)) unlinkSync(path); }
export function connection() {
  const path=join(stateDir(),'connection.json');
  if (!existsSync(path)) throw new Error('Run hypr-watch usb first with the phone connected.');
  return z.object({url:z.string().url(),token:z.string()}).parse(JSON.parse(readFileSync(path,'utf8')));
}

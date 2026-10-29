import { pbkdf2, createHmac, randomBytes, timingSafeEqual } from 'node:crypto';
import { promisify } from 'node:util';
import { z } from 'zod';
import { lanRequest } from './lan.ts';
import type { Endpoint } from './lan.ts';
import { savePairing } from './state.ts';
import type { Pairing } from './state.ts';

export function normaliseCode(code:string):string {
  const result=code.toUpperCase().replace(/[ -]/g,'').replace(/O/g,'0').replace(/[IL]/g,'1');
  if (result.length!==8 || !/^[0-9ABCDEFGHJKMNPQRSTVWXYZ]{8}$/.test(result)) throw new Error('Enter the 8-character code shown on the phone.');
  return result;
}
export async function pairingKey(code:string,spki:Buffer):Promise<Buffer> {
  return promisify(pbkdf2)(normaliseCode(code),Buffer.concat([Buffer.from('hypr-watch pair v1'),spki]),200000,32,'sha256');
}
export function pairingProof(key:Buffer,label:'client'|'phone',spki:Buffer,nonce:Buffer,token=''):string {
  return createHmac('sha256',key).update(Buffer.concat([Buffer.from(label),spki,nonce,Buffer.from(token)])).digest('base64url');
}
const replySchema=z.object({v:z.literal(1),phone_id:z.string().length(22).regex(/^[A-Za-z0-9_-]{22}$/),phone_name:z.string(),agent_id:z.string().length(22).regex(/^[A-Za-z0-9_-]{22}$/),token:z.string().length(48).regex(/^hwa1_[A-Za-z0-9_-]{43}$/),proof:z.string().length(43).regex(/^[A-Za-z0-9_-]{43}$/)});
export async function pair(endpoint:Endpoint,code:string,name:string,store:(record:Pairing)=>void=savePairing):Promise<Pairing> {
  normaliseCode(code);
  if (!name.length || [...name].length>64) throw new Error('name must be 1 to 64 characters.');
  const nonce=randomBytes(16);
  const response=await lanRequest(endpoint,{path:'/pair',method:'POST',maxResponseBytes:4096,prepare:async spki=>{
    const key=await pairingKey(code,spki);
    return {v:1,name,nonce:nonce.toString('base64url'),proof:pairingProof(key,'client',spki,nonce)};
  }});
  const reply=replySchema.parse(response.body),key=await pairingKey(code,response.spki);
  const expected=Buffer.from(pairingProof(key,'phone',response.spki,nonce,reply.token),'base64url'),actual=Buffer.from(reply.proof,'base64url');
  if (actual.length!==expected.length || !timingSafeEqual(actual,expected)) throw new Error('The phone could not prove it knows the code. Pairing was not saved.');
  const record={phone_id:reply.phone_id,phone_name:reply.phone_name,spki_sha256_hex:response.spki.toString('hex'),agent_id:reply.agent_id,token:reply.token,...endpoint,last_success_at:new Date().toISOString()};
  store(record);return record;
}

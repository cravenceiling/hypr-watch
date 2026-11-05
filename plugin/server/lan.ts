import tls from 'node:tls';
import http from 'node:http';
import { createHash } from 'node:crypto';
import { z } from 'zod';

export const IDENTITY_ERROR="This phone's identity changed or someone is intercepting. Run `npx hypr-watch setup` again.";
export const REVOKED_ERROR='This computer was revoked on the phone. Run `npx hypr-watch setup` again.';
export class LanError extends Error {
  readonly kind:'connect'|'pin'|'revoked'|'request';
  readonly requestWritten:boolean;
  constructor(message:string,kind:LanError['kind'],requestWritten:boolean) {super(message);this.kind=kind;this.requestWritten=requestWritten;}
}
export type Endpoint={host:string;port:number};
export type JsonObject=Record<string,unknown>;
export function spkiHash(socket:tls.TLSSocket):Buffer {
  const cert=socket.getPeerX509Certificate();
  if (!cert) throw new Error('The phone did not send a TLS certificate.');
  return createHash('sha256').update(cert.publicKey.export({type:'spki',format:'der'})).digest();
}
export async function lanRequest(endpoint:Endpoint,options:{path:string;method?:string;data?:unknown;token?:string;pin?:string;timeout?:number;connectTimeout?:number;maxResponseBytes?:number;prepare?:(spki:Buffer)=>Promise<unknown>}):Promise<{body:JsonObject;spki:Buffer}> {
  return new Promise((resolve,reject)=>{
    let written=false,settled=false;
    const socket=tls.connect({...endpoint,rejectUnauthorized:false,minVersion:'TLSv1.2'});
    const fail=(error:unknown)=>{
      if (settled) return;settled=true;clearTimeout(timer);socket.destroy();
      reject(error instanceof LanError?error:new LanError(error instanceof Error?error.message:String(error),written?'request':'connect',written));
    };
    const timer=setTimeout(()=>fail(new Error('Phone connection timed out.')),options.connectTimeout??5000);
    socket.on('error',fail);
    socket.once('secureConnect',()=>{void (async()=>{
      const spki=spkiHash(socket);
      if (options.pin && spki.toString('hex')!==options.pin) throw new LanError(IDENTITY_ERROR,'pin',false);
      const data=options.prepare?await options.prepare(spki):options.data;
      clearTimeout(timer);
      const body=data===undefined?undefined:Buffer.from(JSON.stringify(data));
      const headers:Record<string,string|number>={'Content-Type':'application/json'};
      if (options.token) headers.Authorization='Bearer '+options.token;
      if (body) headers['Content-Length']=body.length;
      const request=http.request({host:endpoint.host,port:endpoint.port,path:options.path,method:options.method??'GET',headers,createConnection:()=>socket},response=>{
        const chunks:Buffer[]=[];
        let size=0;
        response.on('data',(chunk:Buffer)=>{
          size+=chunk.length;
          if(options.maxResponseBytes!==undefined && size>options.maxResponseBytes) {fail(new Error('Phone response is too large.'));response.destroy();return;}
          chunks.push(chunk);
        });response.on('error',fail);
        response.on('end',()=>{
          try {
            if (response.statusCode===401) throw new LanError(REVOKED_ERROR,'revoked',true);
            const result=z.record(z.string(),z.unknown()).parse(JSON.parse(Buffer.concat(chunks).toString('utf8')));
            if ((response.statusCode??500)>=400) throw new LanError(typeof result.error==='string'?result.error:`HTTP ${response.statusCode}`,'request',true);
            if (!settled) { settled=true;resolve({body:result,spki}); }
          } catch(error) { fail(error); }
          finally { socket.destroy(); }
        });
      });
      request.on('error',fail);request.setTimeout((options.timeout??40)*1000,()=>fail(new Error('Phone API request timed out.')));
      // Once queued, a mutation may have reached the phone even if the reply is lost.
      written=true;request.end(body);
    })().catch(fail);});
  });
}

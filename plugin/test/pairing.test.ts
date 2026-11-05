import test from 'node:test';
import assert from 'node:assert/strict';
import { createServer } from 'node:https';
import { createServer as createTcpServer } from 'node:net';
import type { IncomingMessage,ServerResponse } from 'node:http';
import { execFileSync,spawn } from 'node:child_process';
import { mkdtempSync,mkdirSync,writeFileSync,readFileSync,rmSync,existsSync,statSync,readdirSync,symlinkSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { X509Certificate,createHash,pbkdf2Sync,createHmac,timingSafeEqual } from 'node:crypto';
import { once } from 'node:events';
import { z } from 'zod';
import { pair,normaliseCode,pairingKey,pairingProof } from '../server/pairing.ts';
import { lanRequest,LanError,IDENTITY_ERROR,REVOKED_ERROR } from '../server/lan.ts';
import { createCall } from '../server/watch_client.ts';
import { savePairing,readPairing,unpair,stateDir } from '../server/state.ts';
import type { Pairing } from '../server/state.ts';

const code='K7Q2M9XA',phoneId=Buffer.alloc(16,1).toString('base64url'),agentId=Buffer.alloc(16,2).toString('base64url'),token='hwa1_'+Buffer.alloc(32,3).toString('base64url');
const vectorSchema=z.object({cases:z.array(z.object({code:z.string(),spki_hex:z.string(),nonce_hex:z.string(),token:z.string(),normalised_code:z.string(),k_hex:z.string(),client_proof:z.string(),phone_proof:z.string()}))});
test('shared Node and Android pairing vectors',async()=>{
  const vectors=vectorSchema.parse(JSON.parse(readFileSync(new URL('../../tests/agent-pairing-vectors.json',import.meta.url),'utf8')));
  assert.ok(vectors.cases.length>=4);
  for(const v of vectors.cases) {
    const spki=Buffer.from(v.spki_hex,'hex'),nonce=Buffer.from(v.nonce_hex,'hex'),key=await pairingKey(v.code,spki);
    assert.equal(normaliseCode(v.code),v.normalised_code);assert.equal(key.toString('hex'),v.k_hex);
    assert.equal(pairingProof(key,'client',spki,nonce),v.client_proof);assert.equal(pairingProof(key,'phone',spki,nonce,v.token),v.phone_proof);
  }
  for(const invalid of ['','1234567','123456789','U1234567','K7Q2/M9XA','K7Q2M9XA\n']) assert.throws(()=>normaliseCode(invalid),/8-character/);
});
function certificate(dir:string,name:string) {
  const key=join(dir,name+'.key'),cert=join(dir,name+'.pem');
  execFileSync('openssl',['req','-x509','-newkey','ec','-pkeyopt','ec_paramgen_curve:P-256','-nodes','-keyout',key,'-out',cert,'-days','1','-subj','/CN=Fake phone'],{stdio:'pipe'});
  const pem=readFileSync(cert),spki=createHash('sha256').update(new X509Certificate(pem).publicKey.export({type:'spki',format:'der'})).digest();
  return {key:readFileSync(key),cert:pem,spki};
}
function phoneProof(spki:Buffer,nonce:Buffer,codeValue:string,label:string,tail=''):string {
  const key=pbkdf2Sync(codeValue,Buffer.concat([Buffer.from('hypr-watch pair v1'),spki]),200000,32,'sha256');
  return createHmac('sha256',key).update(Buffer.concat([Buffer.from(label),spki,nonce,Buffer.from(tail)])).digest('base64url');
}
async function listen(identity:ReturnType<typeof certificate>,handler:(req:IncomingMessage,res:ServerResponse)=>void) {
  const server=createServer(identity,handler);server.listen(0,'127.0.0.1');await once(server,'listening');
  const address=server.address();assert.ok(address && typeof address!=='string');
  return {server,endpoint:{host:'127.0.0.1',port:address.port},close:async()=>{server.closeAllConnections();await new Promise<void>(resolve=>server.close(()=>resolve()));}};
}
async function body(req:IncomingMessage):Promise<Record<string,unknown>> {
  const chunks:Buffer[]=[];for await(const chunk of req) chunks.push(Buffer.from(chunk));
  return z.record(z.string(),z.unknown()).parse(JSON.parse(Buffer.concat(chunks).toString()));
}
function json(res:ServerResponse,status:number,value:unknown) {res.writeHead(status,{'Content-Type':'application/json'});res.end(JSON.stringify(value));}

test('real TLS pairing, wrong codes, MITM relay, fake phone, pins, revoke and private persistence',async t=>{
  const dir=mkdtempSync(join(tmpdir(),'hypr-pair-')),prior=process.env.XDG_STATE_HOME;process.env.XDG_STATE_HOME=dir;
  const identity=certificate(dir,'phone'),mitmIdentity=certificate(dir,'mitm');let requests=0,revoked=false,pairRequests=0;
  const phone=await listen(identity,(req,res)=>{void(async()=>{
    requests++;
    if(req.url==='/pair') {
      pairRequests++;
      const data=await body(req),nonce=Buffer.from(z.string().parse(data.nonce),'base64url');
      const expected=Buffer.from(phoneProof(identity.spki,nonce,code,'client'),'base64url'),actual=Buffer.from(z.string().parse(data.proof),'base64url');
      if(actual.length!==expected.length || !timingSafeEqual(actual,expected)) {json(res,403,{error:"That code didn't match. 4 tries left."});return;}
      json(res,200,{v:1,phone_id:phoneId,phone_name:'Test phone',agent_id:agentId,token,proof:phoneProof(identity.spki,nonce,code,'phone',token)});return;
    }
    if(revoked || req.headers.authorization!=='Bearer '+token) {json(res,401,{error:'Unauthorized'});return;}
    if(req.url==='/api/drop') {req.socket.destroy();return;}
    json(res,200,{ok:true,connection:'Wi-Fi (TLS)'});
  })().catch(error=>{json(res,500,{error:String(error)});});});
  try {
    await t.test('success authenticates both proofs and saves a 0600 record in a 0700 directory',async()=>{
      const paired=await pair(phone.endpoint,'k7q2-m9xa','Test computer');
      assert.equal(paired.spki_sha256_hex,identity.spki.toString('hex'));assert.equal(paired.token,token);assert.deepEqual(readPairing(),paired);
      assert.equal(statSync(stateDir()).mode&0o777,0o700);assert.equal(statSync(join(stateDir(),'pairing.json')).mode&0o777,0o600);
      assert.deepEqual(readdirSync(stateDir()),['pairing.json']);
    });
    await t.test('wrong code stores nothing',async()=>{
      unpair();await assert.rejects(pair(phone.endpoint,'0000-0000','Test computer'),{message:"That code didn't match. 4 tries left."});assert.equal(readPairing(),null);
    });
    await t.test('a MITM using a different cert cannot relay the client proof to the phone',async()=>{
      const mitm=await listen(mitmIdentity,(req,res)=>{void(async()=>{
        const data=await body(req);
        try {const response=await lanRequest(phone.endpoint,{path:'/pair',method:'POST',data});json(res,200,response.body);}
        catch(error) {json(res,403,{error:error instanceof Error?error.message:String(error)});}
      })();});
      try {const before=pairRequests;await assert.rejects(pair(mitm.endpoint,code,'Test computer'),/code didn't match/);assert.equal(pairRequests,before+1);assert.equal(readPairing(),null);}
      finally {await mitm.close();}
    });
    await t.test('a fake phone without the code cannot create the phone proof',async()=>{
      const fake=await listen(mitmIdentity,(req,res)=>{req.resume();json(res,200,{v:1,phone_id:phoneId,phone_name:'Fake',agent_id:agentId,token,proof:Buffer.alloc(32).toString('base64url')});});
      try {await assert.rejects(pair(fake.endpoint,code,'Test computer'),/could not prove/);assert.equal(readPairing(),null);}
      finally {await fake.close();}
    });
    for(const chunked of [false,true]) {
      await t.test('oversized pairing response is bounded before trust, chunked '+chunked,async()=>{
        const fake=await listen(mitmIdentity,(req,res)=>{
          req.resume();
          if(!chunked) res.setHeader('Content-Length',16384);
          res.write(' '.repeat(4096));res.write(' '.repeat(4096));res.end(' '.repeat(8192));
        });
        try {await assert.rejects(pair(fake.endpoint,code,'Test computer'),/response is too large/);assert.equal(readPairing(),null);}
        finally {await fake.close();}
      });
    }
    const paired:Pairing={phone_id:phoneId,phone_name:'Test phone',agent_id:agentId,token,spki_sha256_hex:identity.spki.toString('hex'),...phone.endpoint};savePairing(paired);
    const bin=join(dir,'bin');mkdirSync(bin);symlinkSync(process.execPath,join(bin,'node'));
    const agentLog=join(dir,'agent.log');
    for(const agent of ['claude','codex']) writeFileSync(join(bin,agent),`#!/usr/bin/env node
require('node:fs').appendFileSync(process.env.TEST_AGENT_LOG,JSON.stringify({agent:'${agent}',args:process.argv.slice(2)})+'\\n');
`,{mode:0o755});
    const setup=async(args:string[],input='')=>{
      const child=spawn(process.execPath,[new URL('../server/cli.ts',import.meta.url).pathname,'setup',...args],{
        env:{...process.env,PATH:bin,TEST_AGENT_LOG:agentLog,HYPR_WATCH_TRANSPORT:'usb'},stdio:['pipe','pipe','pipe'],timeout:10000,
      });
      let stdout='',stderr='';child.stdout.setEncoding('utf8').on('data',chunk=>{stdout+=chunk;});child.stderr.setEncoding('utf8').on('data',chunk=>{stderr+=chunk;});
      child.stdin.end(input);const [status]=await once(child,'close');
      assert.equal(status,0,stderr);assert.ok(!stdout.includes(token));assert.ok(!stderr.includes(token));return stdout;
    };
    await t.test('setup checks a working pairing over LAN even when USB is configured, then registers without a code',async()=>{
      const before=pairRequests,stdout=await setup(['--yes']);
      assert.match(stdout,/Already paired with Test phone/);assert.match(stdout,/Phone: Test phone. Agents registered: Claude Code, Codex/);
      assert.match(stdout,/Restart Claude Code and Codex, then ask: how did I sleep this week\?/);
      assert.doesNotMatch(stdout,/Code shown|Add Hypr Watch/);assert.equal(pairRequests,before);
    });
    await t.test('setup explains revocation, pairs again and respects agent flags',async()=>{
      revoked=true;const before=pairRequests;
      try {
        const stdout=await setup(['--host',`127.0.0.1:${phone.endpoint.port}`,'--name','Setup computer','--codex','--yes'],'\n'+code+'\n');
        assert.match(stdout,/revoked on the phone/);assert.match(stdout,/Paired with Test phone/);assert.match(stdout,/Agents registered: Codex/);
        assert.match(stdout,/Press Enter when Connect an agent is open/);
        assert.doesNotMatch(stdout,/Add Hypr Watch/);assert.equal(pairRequests,before+1);
      } finally {revoked=false;}
    });
    await t.test('setup explains a changed identity and buffers prompt answers through pairing',async()=>{
      savePairing({...paired,spki_sha256_hex:mitmIdentity.spki.toString('hex')});const before=pairRequests;
      const stdout=await setup(['--host',`127.0.0.1:${phone.endpoint.port}`],'\n'+code+'\n\nn\n');
      assert.match(stdout,/identity changed/);assert.match(stdout,/Add Hypr Watch to Claude Code\? \[Y\/n\]/);assert.match(stdout,/Add Hypr Watch to Codex\? \[Y\/n\]/);
      assert.match(stdout,/Press Enter when Connect an agent is open/);
      assert.match(stdout,/Agents registered: Claude Code\./);assert.equal(pairRequests,before+1);
    });
    await t.test('fresh setup and pair share the code pairing path',async()=>{
      unpair();const before=pairRequests;
      const stdout=await setup(['--host',`127.0.0.1:${phone.endpoint.port}`,'--yes'],code+'\n');
      assert.doesNotMatch(stdout,/Already paired/);assert.match(stdout,/Paired with Test phone/);assert.equal(pairRequests,before+1);
    });
    savePairing(paired);
    await t.test('subsequent pinned requests succeed',async()=>assert.deepEqual((await lanRequest(paired,{path:'/api/status',pin:paired.spki_sha256_hex,token})).body,{ok:true,connection:'Wi-Fi (TLS)'}));
    await t.test('pin mismatch writes zero HTTP bytes and never discovers or falls back',async()=>{
      const before=requests;let usb=0,discovery=0;
      const call=createCall({pairing:()=>({...paired,spki_sha256_hex:mitmIdentity.spki.toString('hex')}),mode:()=>'auto',usb:async()=>{usb++;return {};},discover:async()=>{discovery++;return [];}});
      await assert.rejects(call('status'),{message:IDENTITY_ERROR});assert.equal(requests,before);assert.equal(usb,0);assert.equal(discovery,0);assert.deepEqual(readPairing(),paired);
    });
    await t.test('401 after revoke is terminal and retains credentials for an explicit re-pair',async()=>{
      revoked=true;let usb=0;
      const call=createCall({pairing:()=>paired,mode:()=>'auto',usb:async()=>{usb++;return {};}});
      await assert.rejects(call('status'),{message:REVOKED_ERROR});assert.equal(usb,0);assert.deepEqual(readPairing(),paired);revoked=false;
    });
    await t.test('lost reply after POST never retries or falls back',async()=>{
      let usb=0,discovery=0;const before=requests;
      const call=createCall({pairing:()=>paired,mode:()=>'auto',usb:async()=>{usb++;return {};},discover:async()=>{discovery++;return [];}});
      await assert.rejects(call('drop','POST',{}),error=>error instanceof LanError && error.requestWritten);
      assert.equal(requests,before+1);assert.equal(usb,0);assert.equal(discovery,0);
    });
    await t.test('cached connect failure rediscovers by phone id, pins and saves the new address',async()=>{
      const unused=await createUnusedPort();const saved:Pairing[]=[];let usb=0;
      const call=createCall({pairing:()=>({...paired,port:unused}),mode:()=>'auto',save:value=>{saved.push(value);},discover:async()=>[{...phone.endpoint,phone_id:'wrong',name:'Wrong',pairing:false},{...phone.endpoint,phone_id:phoneId,name:'Phone',pairing:false}],usb:async()=>{usb++;return {};}});
      assert.deepEqual(await call('status'),{ok:true,connection:'Wi-Fi (TLS)'});assert.deepEqual(saved,[{...paired,last_success_at:saved[0].last_success_at}]);assert.ok(saved[0].last_success_at);assert.equal(usb,0);
    });
  } finally {await phone.close();if(prior===undefined) delete process.env.XDG_STATE_HOME;else process.env.XDG_STATE_HOME=prior;rmSync(dir,{recursive:true,force:true});}
});
async function createUnusedPort():Promise<number> {
  const server=createTcpServer();server.listen(0,'127.0.0.1');await once(server,'listening');const address=server.address();assert.ok(address && typeof address!=='string');
  await new Promise<void>(resolve=>server.close(()=>resolve()));return address.port;
}
test('transport fallback matrix',async()=>{
  const paired:Pairing={phone_id:phoneId,phone_name:'Phone',agent_id:agentId,token,spki_sha256_hex:'00'.repeat(32),host:'127.0.0.1',port:await createUnusedPort()};
  let usb=0,discoveries=0;
  const dependencies={pairing:()=>paired,usbAvailable:()=>true,usb:async()=>{usb++;return {usb:true};},discover:async()=>{discoveries++;return [];}};
  assert.deepEqual(await createCall({...dependencies,mode:()=>'auto'})('status'),{usb:true});assert.equal(usb,1);assert.equal(discoveries,1);
  await assert.rejects(createCall({...dependencies,mode:()=>'lan'})('status'),error=>error instanceof LanError && !error.requestWritten);assert.equal(usb,1);
  assert.deepEqual(await createCall({...dependencies,mode:()=>'auto',pairing:()=>null})('status'),{usb:true});assert.equal(usb,2);
  await assert.rejects(createCall({...dependencies,mode:()=>'lan',pairing:()=>null})('status'),/No phone is paired/);assert.equal(usb,2);
  assert.deepEqual(await createCall({...dependencies,mode:()=>'usb',pairing:()=>{throw new Error('must not read pairing');}})('status'),{usb:true});assert.equal(usb,3);
  await assert.rejects(createCall({...dependencies,mode:()=>'invalid'})('status'),/HYPR_WATCH_TRANSPORT/);
  for(const error of [new LanError(IDENTITY_ERROR,'pin',false),new LanError(REVOKED_ERROR,'revoked',true),new LanError('write failed','request',true)]) {
    await assert.rejects(createCall({...dependencies,mode:()=>'auto',lan:async()=>{throw error;}})('create','POST',{}),{message:error.message});assert.equal(usb,3);
  }
  let attempts=0,saved=0;
  const retryDeps={...dependencies,discover:async()=>[{host:'127.0.0.1',port:paired.port+1,phone_id:phoneId,name:'Phone',pairing:false}],save:()=>{saved++;},mode:()=>'auto'};
  assert.deepEqual(await createCall({...retryDeps,lan:async()=>{attempts++;throw new LanError('offline','connect',false);}})('status'),{usb:true});assert.equal(attempts,2);assert.equal(usb,4);assert.equal(saved,0);
  attempts=0;
  await assert.rejects(createCall({...retryDeps,lan:async()=>{attempts++;throw attempts===1?new LanError('offline','connect',false):new LanError(IDENTITY_ERROR,'pin',false);}})('status'),{message:IDENTITY_ERROR});assert.equal(attempts,2);assert.equal(usb,4);
});
for(const mode of ['auto','lan']) {
  test('rediscovery tries every matching candidate in '+mode,async()=>{
    const paired:Pairing={phone_id:phoneId,phone_name:'Phone',agent_id:agentId,token,spki_sha256_hex:'00'.repeat(32),host:'192.0.2.1',port:8767};
    const attempts:string[]=[],saved:Pairing[]=[];
    const call=createCall({mode:()=>mode,pairing:()=>paired,save:value=>saved.push(value),usb:async()=>assert.fail('USB must not run'),discover:async()=>[
      {host:'192.0.2.2',port:8767,phone_id:'wrong',name:'Wrong phone',pairing:false},
      {host:'192.0.2.3',port:8767,phone_id:phoneId,name:'Phone',pairing:false},
      {host:'192.0.2.4',port:8767,phone_id:phoneId,name:'Phone',pairing:false},
    ],lan:async endpoint=>{
      attempts.push(endpoint.host);
      if(endpoint.host!=='192.0.2.4') throw new LanError('offline','connect',false);
      return {body:{ok:true},spki:Buffer.alloc(32)};
    }});
    assert.deepEqual(await call('status'),{ok:true});assert.deepEqual(attempts,['192.0.2.1','192.0.2.3','192.0.2.4']);
    assert.equal(saved[0].host,'192.0.2.4');assert.ok(saved[0].last_success_at);
  });
}
test('paired phone unreachable without USB reports Wi-Fi and invalid note IDs never reach transport',async()=>{
  const paired:Pairing={phone_id:phoneId,phone_name:'Phone',agent_id:agentId,token,spki_sha256_hex:'00'.repeat(32),host:'192.0.2.1',port:8767};
  let attempts=0,discoveries=0;
  const call=createCall({mode:()=>'auto',pairing:()=>paired,usbAvailable:()=>false,usb:async()=>assert.fail('USB must not run'),discover:async()=>{discoveries++;return [];},lan:async()=>{attempts++;throw new LanError('offline','connect',false);}});
  for(const id of ['','42\n','../status','a b','id?x=1','id#fragment','%20']) await assert.rejects(call('notes/'+id,'PATCH',{}),/note_id must/);
  assert.equal(attempts,0);assert.equal(discoveries,0);
  await assert.rejects(call('status'),/Phone is unreachable on Wi-Fi/);assert.equal(attempts,1);assert.equal(discoveries,1);
});

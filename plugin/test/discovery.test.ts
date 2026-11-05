import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync,writeFileSync,rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import Bonjour from 'bonjour-service';
import { discover,discoverNative } from '../server/discovery.ts';

const phoneId='AQEBAQEBAQEBAQEBAQEBAQ';
test('discovery retains all addresses, deduplicates endpoints and queries again',async()=>{
  const find=Bonjour.prototype.find;
  let queries=0,stopped=false;
  const mock=test.mock.method(Bonjour.prototype,'find',function(this:Bonjour,options:Parameters<Bonjour['find']>[0],onup:Parameters<Bonjour['find']>[1]) {
    const browser=find.call(this,options);
    test.mock.method(browser,'update',()=>{queries++;});
    const stop=browser.stop.bind(browser);
    test.mock.method(browser,'stop',()=>{stopped=true;stop();});
    const service=new Bonjour.Service({name:'Test phone',type:'hyprwatch',port:8767,txt:{v:'1',id:phoneId,pair:'1'}},()=>{},()=>{});
    service.addresses=['192.0.2.1','192.0.2.2','192.0.2.1'];
    onup?.(service);onup?.(service);
    return browser;
  });
  try {
    const result=await discover(1100);
    assert.deepEqual(result,[{host:'192.0.2.1',port:8767,phone_id:phoneId,name:'Test phone',pairing:true},{host:'192.0.2.2',port:8767,phone_id:phoneId,name:'Test phone',pairing:true}]);
    assert.ok(queries>=2);assert.equal(stopped,true);
  } finally {mock.mock.restore();}
});
test('native discovery parses cached macOS and Linux answers on repeated calls',async()=>{
  const dir=mkdtempSync(join(tmpdir(),'hypr-discovery-')),path=process.env.PATH;
  process.env.PATH=dir+':'+path;
  writeFileSync(join(dir,'dns-sd'),`#!/usr/bin/env node
if(process.argv[2]!=='-t' || process.argv[3]!=='1') process.exit(1);
if(process.argv.includes('-B')) console.log('12:00:00.000 Add 2 14 local. _hyprwatch._tcp. Test phone');
else console.log('Test phone._hyprwatch._tcp.local. can be reached at test-phone.local.:8767 (interface 14)\\n v=1 id=${phoneId} pair=1');
`,{mode:0o755});
  writeFileSync(join(dir,'avahi-browse'),`#!/usr/bin/env node
console.log('=;eth0;IPv4;Test\\\\032phone;_hyprwatch._tcp;local;test-phone.local;192.0.2.1;8767;"v=1" "id=${phoneId}" "pair=0"');
console.log('=;eth0;IPv6;Test\\\\032phone;_hyprwatch._tcp;local;test-phone.local;fe80::1;8767;"v=1" "id=${phoneId}" "pair=0"');
console.log('=;eth0;IPv4;Wrong version;_hyprwatch._tcp;local;test-phone.local;192.0.2.2;8767;"v=2" "id=wrong"');
`,{mode:0o755});
  try {
    for(let i=0;i<10;i++) assert.deepEqual(await discoverNative(1000,'darwin'),[{host:'test-phone.local.',port:8767,phone_id:phoneId,name:'Test phone',pairing:true}]);
    if(process.platform==='darwin') {
      const find=Bonjour.prototype.find;
      const mock=test.mock.method(Bonjour.prototype,'find',function(this:Bonjour,options:Parameters<Bonjour['find']>[0]) {return find.call(this,options);});
      try {
        assert.deepEqual(await discover(1000),[{host:'test-phone.local.',port:8767,phone_id:phoneId,name:'Test phone',pairing:true}]);
      } finally {mock.mock.restore();}
    }
    assert.deepEqual(await discoverNative(1000,'linux'),[
      {host:'192.0.2.1',port:8767,phone_id:phoneId,name:'Test phone',pairing:false},
      {host:'fe80::1%eth0',port:8767,phone_id:phoneId,name:'Test phone',pairing:false},
    ]);
    assert.deepEqual(await discoverNative(1000,'win32'),[]);
    writeFileSync(join(dir,'dns-sd'),'#!/usr/bin/env node\nprocess.stdout.write("No services");setInterval(()=>{},1000);\n',{mode:0o755});
    assert.deepEqual(await discoverNative(150,'darwin'),[]);
  } finally {if(path===undefined) delete process.env.PATH;else process.env.PATH=path;rmSync(dir,{recursive:true,force:true});}
});

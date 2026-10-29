import Bonjour from 'bonjour-service';
import { execFile } from 'node:child_process';
export type Candidate={host:string;port:number;phone_id:string;name:string;pairing:boolean};

function capture(command:string,args:string[],milliseconds:number):Promise<string> {
  return new Promise(resolve=>execFile(command,args,{timeout:milliseconds,maxBuffer:65536},(_error,stdout)=>resolve(stdout)));
}
export async function discoverNative(milliseconds=2000,platform:NodeJS.Platform=process.platform):Promise<Candidate[]> {
  const found:Candidate[]=[];
  if(platform==='darwin') {
    // dns-sd flushes piped output on its own deadline, but loses it when killed.
    const seconds=Math.max(1,Math.ceil(milliseconds/1000)),deadline=seconds*1000+1000;
    const browse=await capture('dns-sd',['-t',String(seconds),'-B','_hyprwatch._tcp','local.'],deadline);
    const services=new Map<string,{name:string;domain:string}>();
    for(const line of browse.split('\n')) {
      const match=/\bAdd\s+\S+\s+\d+\s+(\S+)\s+_hyprwatch\._tcp\.\s+(.+)$/.exec(line.trim());
      if(match) services.set(match[2]+'\0'+match[1],{name:match[2],domain:match[1]});
    }
    await Promise.all([...services.values()].map(async service=>{
      const answer=await capture('dns-sd',['-t',String(seconds),'-L',service.name,'_hyprwatch._tcp',service.domain],deadline);
      const endpoint=/can be reached at (\S+):(\d+) \(interface \d+\)/.exec(answer);
      const id=/(?:^|[\s"])id=([A-Za-z0-9_-]+)(?=[\s"]|$)/m.exec(answer);
      if(!endpoint || !id || !/(?:^|[\s"])v=1(?=[\s"]|$)/m.test(answer)) return;
      const port=Number(endpoint[2]);
      if(port<1 || port>65535) return;
      found.push({host:endpoint[1],port,phone_id:id[1],name:service.name,pairing:/(?:^|[\s"])pair=1(?=[\s"]|$)/m.test(answer)});
    }));
  } else if(platform==='linux') {
    const browse=await capture('avahi-browse',['--resolve','--parsable','--terminate','_hyprwatch._tcp'],milliseconds);
    for(const line of browse.split('\n')) {
      const [event,iface,,name,type,,hostname,address,portValue,txt]=line.split(';');
      if(event!=='=' || type!=='_hyprwatch._tcp' || !address || !hostname || !txt) continue;
      const id=/(?:^|[\s"])id=([A-Za-z0-9_-]+)(?=[\s"]|$)/.exec(txt),port=Number(portValue);
      if(!id || !/(?:^|[\s"])v=1(?=[\s"]|$)/.test(txt) || !Number.isInteger(port) || port<1 || port>65535) continue;
      const host=/^fe80:/i.test(address) && !address.includes('%')?address+'%'+iface:address;
      found.push({host,port,phone_id:id[1],name:name.replace(/\\(\d{3})/g,(_match,digits:string)=>String.fromCharCode(Number(digits))),pairing:/(?:^|[\s"])pair=1(?=[\s"]|$)/.test(txt)});
    }
  }
  return found;
}
export async function discover(milliseconds=2000):Promise<Candidate[]> {
  const bonjour=new Bonjour({},()=>{}),found=new Map<string,Candidate>();
  const browser=bonjour.find({type:'hyprwatch',protocol:'tcp'},service=>{
    if (String(service.txt?.v)!=='1' || typeof service.txt?.id!=='string') return;
    const hosts=service.addresses?.length?service.addresses:[service.referer?.address];
    for(const host of hosts) if(host) {
      const candidate={host,port:service.port,phone_id:service.txt.id,name:service.name,pairing:String(service.txt.pair)==='1'};
      found.set(JSON.stringify([candidate.phone_id,host,candidate.port]),candidate);
    }
  });
  const query=setInterval(()=>browser.update(),500);
  try { await new Promise(resolve=>setTimeout(resolve,milliseconds)); }
  finally { clearInterval(query);browser.stop();bonjour.destroy(); }
  // The system responder keeps a cache even when a repeated multicast query gets no answer.
  return usable(found.size?[...found.values()]:await discoverNative(milliseconds));
}
// IPv4 first: a link-local IPv6 answer has no zone index, so Node can't connect to it.
function usable(candidates:Candidate[]):Candidate[] {
  const rank=(host:string)=>/^\d+\.\d+\.\d+\.\d+$/.test(host)?0:host.includes(':')?2:1;
  return candidates.filter(c=>!/^fe80:/i.test(c.host)).sort((a,b)=>rank(a.host)-rank(b.host));
}
export function parseHost(value:string):{host:string;port:number} {
  const url=new URL('tcp://'+value);
  if (url.username || url.password || !['','/'].includes(url.pathname) || url.search || url.hash || url.port==='0') throw new Error('host must be an IP address or hostname, optionally followed by :port.');
  return {host:url.hostname.replace(/^\[|\]$/g,''),port:url.port?Number(url.port):8767};
}

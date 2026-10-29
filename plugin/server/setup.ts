import { accessSync,constants,existsSync,readFileSync,readdirSync,statSync } from 'node:fs';
import { delimiter,join } from 'node:path';
import { tmpdir } from 'node:os';
import { fileURLToPath } from 'node:url';
import { execFileSync } from 'node:child_process';
import { z } from 'zod';

export type SetupOptions={claude?:boolean;codex?:boolean;yes?:boolean};
export async function registerAgents(options:SetupOptions,question:(prompt:string)=>Promise<string>,packageRoot=new URL('../',import.meta.url)):Promise<string[]> {
  if(process.platform==='win32') throw new Error('Hypr Watch setup supports macOS and Linux. Windows is not supported.');
  const agents=[{command:'claude',name:'Claude Code',scope:['-s','user']},{command:'codex',name:'Codex',scope:[]}];
  const version=z.object({version:z.string()}).parse(JSON.parse(readFileSync(new URL('package.json',packageRoot),'utf8'))).version;
  const source=new URL('server/cli.ts',packageRoot),built=new URL('dist/cli.js',packageRoot);
  const checkout=existsSync(source);
  const [major,minor]=process.versions.node.split('.').map(Number);
  const stripsTypes=major>=24 || (major===23 && minor>=6) || (major===22 && minor>=18);
  if(checkout && !stripsTypes) {
    if(!existsSync(built)) throw new Error('This Node version cannot run TypeScript directly. Run npm run build in plugin/ or upgrade to Node 22.18 or later.');
    const server=new URL('server/',packageRoot),builtTime=statSync(built).mtimeMs;
    if(readdirSync(server,{recursive:true,encoding:'utf8'}).some(path=>statSync(join(fileURLToPath(server),path)).mtimeMs>builtTime)) {
      process.stderr.write('Warning: dist is older than server/. Run npm run build in plugin/ before launching an agent.\n');
    }
  }
  const launch=checkout?[process.execPath,fileURLToPath(stripsTypes?source:built),'mcp']:['npx','--prefer-offline','--fetch-retries=0','--fetch-timeout=5000','-y',`hypr-watch@${version}`,'mcp'];
  const registered:string[]=[];
  let missing=false;
  for(const agent of agents) {
    if((options.claude || options.codex) && !(agent.command==='claude'?options.claude:options.codex)) continue;
    const executable=(process.env.PATH??'').split(delimiter).map(dir=>join(dir,agent.command)).find(path=>{
      try {accessSync(path,constants.X_OK);return true;} catch {return false;}
    });
    if(!executable) {
      if(agent.command==='claude'?options.claude:options.codex) throw new Error(`${agent.name} was not found on PATH. Install it before running setup with --${agent.command}.`);
      process.stdout.write(`${agent.name} was not found on PATH. Skipped.\n`);missing=true;continue;
    }
    if(!options.yes && !/^(?:y|yes)?$/i.test((await question(`Add Hypr Watch to ${agent.name}? [Y/n] `)).trim())) continue;
    const run=(args:string[])=>execFileSync(executable,args,{encoding:'utf8',stdio:['ignore','pipe','pipe'],timeout:30000});
    const env=process.env.XDG_STATE_HOME===undefined?[]:[agent.command==='claude'?'-e':'--env',`XDG_STATE_HOME=${process.env.XDG_STATE_HOME}`];
    const args=['mcp','add',...agent.scope,...env,'hypr-watch','--',...launch];
    let removed=false;
    // Both CLIs can report a missing entry on the first setup.
    try {run(['mcp','remove',...agent.scope,'hypr-watch']);removed=true;} catch {}
    try {
      run(args);
    } catch(error) {
      const detail=error instanceof Error?error.message:String(error);
      const stderr=error instanceof Error && 'stderr' in error?String(error.stderr??'').trim():'';
      const retry=[executable,...args].map(arg=>`'${arg.replaceAll("'","'\\''")}'`).join(' ');
      throw new Error(`Could not register Hypr Watch with ${agent.name}. ${detail}\n${stderr}\n${agent.command==='claude' && removed?'The old hypr-watch entry was removed. ':''}Retry with:\n${retry}`);
    }
    registered.push(agent.name);
    process.stdout.write(`Added Hypr Watch to ${agent.name} using ${launch.join(' ')}.\n`);
  }
  if(!checkout && registered.length) {
    try {execFileSync('npx',[...launch.slice(1,-1),'--help'],{cwd:tmpdir(),encoding:'utf8',stdio:['ignore','pipe','pipe'],timeout:30000});}
    catch(error) {
      const stderr=error instanceof Error && 'stderr' in error?String(error.stderr??'').trim():'';
      process.stderr.write(`Warning: could not warm the Hypr Watch npm cache. ${error instanceof Error?error.message:String(error)}\n${stderr}\nRun npx hypr-watch setup again before launching agents.\n`);
    }
  }
  if(missing) process.stdout.write('Run npx hypr-watch setup again after installing Claude Code or Codex.\n');
  return registered;
}

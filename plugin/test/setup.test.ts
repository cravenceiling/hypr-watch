import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync,mkdirSync,writeFileSync,readFileSync,existsSync,rmSync,symlinkSync,utimesSync,realpathSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { pathToFileURL } from 'node:url';
import { registerAgents } from '../server/setup.ts';

test('registration detects PATH, prompts, filters flags, replaces entries and chooses the launch command',async t=>{
  const dir=mkdtempSync(join(tmpdir(),'hypr-setup-')),bin=join(dir,'bin'),root=join(dir,'plugin'),log=join(dir,'args.jsonl');
  mkdirSync(bin);mkdirSync(root);symlinkSync(process.execPath,join(bin,'node'));
  writeFileSync(join(root,'package.json'),JSON.stringify({version:'0.1.0'}));
  const oldPath=process.env.PATH,oldLog=process.env.TEST_AGENT_LOG,oldState=process.env.XDG_STATE_HOME;
  delete process.env.XDG_STATE_HOME;
  let stdout='',stderr='';
  t.mock.method(process.stdout,'write',(chunk:string)=>{stdout+=chunk;return true;});
  t.mock.method(process.stderr,'write',(chunk:string)=>{stderr+=chunk;return true;});
  process.env.PATH=bin;process.env.TEST_AGENT_LOG=log;
  const packageRoot=pathToFileURL(root+'/'),prompts:string[]=[];
  const question=async(prompt:string)=>{prompts.push(prompt);return '';};
  const noQuestion=async()=>assert.fail('--yes must not prompt');
  const calls=()=>existsSync(log)?readFileSync(log,'utf8').trim().split('\n').map(line=>JSON.parse(line)):[];
  const warmLog=join(dir,'warm.jsonl');
  const clear=()=>{prompts.length=0;stdout='';stderr='';for(const path of [log,warmLog]) if(existsSync(path)) rmSync(path);};
  const install=(agent:string)=>writeFileSync(join(bin,agent),`#!/usr/bin/env node
const fs=require('node:fs'),path=require('node:path');
const agent=path.basename(process.argv[1]),args=process.argv.slice(2),entry=path.join(path.dirname(process.env.TEST_AGENT_LOG),agent+'.json');
fs.appendFileSync(process.env.TEST_AGENT_LOG,JSON.stringify({agent,args})+'\\n');
if(args[1]==='remove') {if(!fs.existsSync(entry))process.exit(1);fs.unlinkSync(entry);}
if(args[1]==='add') {if(fs.existsSync(entry))process.exit(2);fs.writeFileSync(entry,JSON.stringify(args));}
`,{mode:0o755});
  writeFileSync(join(bin,'npx'),`#!/usr/bin/env node
require('node:fs').appendFileSync(${JSON.stringify(warmLog)},JSON.stringify({args:process.argv.slice(2),cwd:process.cwd()})+'\\n');
`,{mode:0o755});
  const launch=['npx','--prefer-offline','--fetch-retries=0','--fetch-timeout=5000','-y','hypr-watch@0.1.0','mcp'];
  try {
    await t.test('no agent found never prompts or runs a command',async()=>{
      assert.deepEqual(await registerAgents({},question,packageRoot),[]);assert.deepEqual(prompts,[]);assert.deepEqual(calls(),[]);
      assert.match(stdout,/after installing/);
      await assert.rejects(registerAgents({claude:true,yes:true},noQuestion,packageRoot),/Claude Code was not found on PATH/);
      await assert.rejects(registerAgents({codex:true,yes:true},noQuestion,packageRoot),/Codex was not found on PATH/);
      assert.equal(existsSync(warmLog),false);
    });
    install('claude');
    await t.test('only a found agent is offered, with a default yes',async()=>{
      clear();assert.deepEqual(await registerAgents({},question,packageRoot),['Claude Code']);
      assert.deepEqual(prompts,['Add Hypr Watch to Claude Code? [Y/n] ']);
      assert.deepEqual(calls(),[{agent:'claude',args:['mcp','remove','-s','user','hypr-watch']},{agent:'claude',args:['mcp','add','-s','user','hypr-watch','--',...launch]}]);
    });
    await t.test('package cache warming uses the launch flags once from the neutral temp directory',()=>{
      assert.deepEqual(JSON.parse(readFileSync(warmLog,'utf8')),{args:[...launch.slice(1,-1),'--help'],cwd:realpathSync(tmpdir())});
    });
    install('codex');
    await t.test('declining runs no remove or add',async()=>{
      clear();assert.deepEqual(await registerAgents({},async prompt=>{prompts.push(prompt);return 'n';},packageRoot),[]);
      assert.deepEqual(prompts,['Add Hypr Watch to Claude Code? [Y/n] ','Add Hypr Watch to Codex? [Y/n] ']);assert.deepEqual(calls(),[]);assert.doesNotMatch(stdout,/after installing/);
      assert.equal(existsSync(warmLog),false);
    });
    await t.test('--yes registers both and replaces an existing entry on every run',async()=>{
      for(let i=0;i<2;i++) {
        clear();assert.deepEqual(await registerAgents({yes:true},noQuestion,packageRoot),['Claude Code','Codex']);
        assert.deepEqual(calls(),[
          {agent:'claude',args:['mcp','remove','-s','user','hypr-watch']},{agent:'claude',args:['mcp','add','-s','user','hypr-watch','--',...launch]},
          {agent:'codex',args:['mcp','remove','hypr-watch']},{agent:'codex',args:['mcp','add','hypr-watch','--',...launch]},
        ]);
        assert.equal(readFileSync(warmLog,'utf8').trim().split('\n').length,1);
      }
    });
    for(const agent of ['claude','codex']) {
      await t.test('--'+agent+' selects only that agent',async()=>{
        clear();assert.deepEqual(await registerAgents({[agent]:true,yes:true},noQuestion,packageRoot),[agent==='claude'?'Claude Code':'Codex']);
        assert.ok(calls().every(call=>call.agent===agent));assert.equal(calls().length,2);
      });
    }
    await t.test('both flags allow both agents',async()=>{
      clear();assert.deepEqual(await registerAgents({claude:true,codex:true,yes:true},noQuestion,packageRoot),['Claude Code','Codex']);assert.equal(calls().length,4);
    });
    await t.test('XDG_STATE_HOME is forwarded using each agent CLI flag',async()=>{
      clear();process.env.XDG_STATE_HOME=join(dir,'state with spaces');
      try {
        await registerAgents({yes:true},noQuestion,packageRoot);
        assert.deepEqual(calls()[1].args,['mcp','add','-s','user','-e',`XDG_STATE_HOME=${process.env.XDG_STATE_HOME}`,'hypr-watch','--',...launch]);
        assert.deepEqual(calls()[3].args,['mcp','add','--env',`XDG_STATE_HOME=${process.env.XDG_STATE_HOME}`,'hypr-watch','--',...launch]);
      } finally {delete process.env.XDG_STATE_HOME;}
    });
    await t.test('warming failure warns without failing registration',async()=>{
      writeFileSync(join(bin,'npx'),'#!/usr/bin/env node\nprocess.stderr.write("cache unavailable");process.exit(1);\n',{mode:0o755});
      clear();assert.deepEqual(await registerAgents({codex:true,yes:true},noQuestion,packageRoot),['Codex']);
      assert.match(stderr,/could not warm/);assert.match(stderr,/cache unavailable/);
    });
    mkdirSync(join(root,'server'));writeFileSync(join(root,'server/cli.ts'),'');
    for(const built of [false,true]) {
      await t.test('checkout prefers source TypeScript with dist present '+built,async()=>{
        if(built) {mkdirSync(join(root,'dist'));writeFileSync(join(root,'dist/cli.js'),'');}
        clear();await registerAgents({codex:true,yes:true},noQuestion,packageRoot);
        assert.deepEqual(calls()[1],{agent:'codex',args:['mcp','add','hypr-watch','--',process.execPath,join(root,'server/cli.ts'),'mcp']});
        assert.equal(existsSync(warmLog),false);
      });
    }
    await t.test('older Node uses dist and warns when it is stale',async()=>{
      const version=process.versions.node;
      Object.defineProperty(process.versions,'node',{value:'22.17.0',configurable:true});
      utimesSync(join(root,'dist/cli.js'),new Date(0),new Date(0));
      try {
        clear();await registerAgents({codex:true,yes:true},noQuestion,packageRoot);
        assert.deepEqual(calls()[1].args,['mcp','add','hypr-watch','--',process.execPath,join(root,'dist/cli.js'),'mcp']);
        assert.match(stderr,/dist is older than server/);
        rmSync(join(root,'dist/cli.js'));
        await assert.rejects(registerAgents({codex:true,yes:true},noQuestion,packageRoot),/Run npm run build/);
      } finally {Object.defineProperty(process.versions,'node',{value:version,configurable:true});}
    });
    await t.test('Windows setup fails clearly before running agents',async()=>{
      const platform=process.platform;
      Object.defineProperty(process,'platform',{value:'win32',configurable:true});
      try {clear();await assert.rejects(registerAgents({},question,packageRoot),/macOS and Linux.*Windows is not supported/);assert.deepEqual(calls(),[]);}
      finally {Object.defineProperty(process,'platform',{value:platform,configurable:true});}
    });
    await t.test('a failed add includes stderr, removal status and a retry command',async()=>{
      writeFileSync(join(bin,'claude'),'#!/usr/bin/env node\nif(process.argv[3]==="add") {process.stderr.write("test configuration failure");process.exit(1); }\n',{mode:0o755});
      await assert.rejects(registerAgents({claude:true,yes:true},noQuestion,packageRoot),error=>{
        assert.ok(error instanceof Error);assert.match(error.message,/Could not register.*Claude Code/);assert.match(error.message,/test configuration failure/);
        assert.match(error.message,/old hypr-watch entry was removed/);assert.match(error.message,/Retry with:[\s\S]*'mcp' 'add' '-s' 'user'/);return true;
      });
    });
    await t.test('a failed remove does not claim an old entry was removed',async()=>{
      writeFileSync(join(bin,'claude'),'#!/usr/bin/env node\nprocess.exit(1);\n',{mode:0o755});
      await assert.rejects(registerAgents({claude:true,yes:true},noQuestion,packageRoot),error=>{
        assert.ok(error instanceof Error);assert.match(error.message,/Could not register.*Claude Code/);
        assert.doesNotMatch(error.message,/old hypr-watch entry was removed/);return true;
      });
    });
  } finally {
    if(oldPath===undefined) delete process.env.PATH;else process.env.PATH=oldPath;
    if(oldState===undefined) delete process.env.XDG_STATE_HOME;else process.env.XDG_STATE_HOME=oldState;
    if(oldLog===undefined) delete process.env.TEST_AGENT_LOG;else process.env.TEST_AGENT_LOG=oldLog;
    rmSync(dir,{recursive:true,force:true});
  }
});

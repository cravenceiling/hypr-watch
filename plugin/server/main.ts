#!/usr/bin/env node
import { McpServer } from '@modelcontextprotocol/sdk/server/mcp.js';
import { StdioServerTransport } from '@modelcontextprotocol/sdk/server/stdio.js';
import { z } from 'zod';
import { pathToFileURL } from 'node:url';
import { realpathSync,readFileSync } from 'node:fs';
import { definitions } from './tool-definitions.ts';
import { createTools } from './tools.ts';
import { call } from './watch_client.ts';
import { phone } from './phone.ts';

export function createServer(tools=createTools(call,phone)):McpServer {
  const {version}=z.object({version:z.string()}).parse(JSON.parse(readFileSync(new URL('../package.json',import.meta.url),'utf8')));
  const server=new McpServer({name:'Hypr Watch',version});
  for(const definition of definitions) {
    server.registerTool(definition.name,{
      description:definition.description,annotations:definition.annotations,inputSchema:definition.inputSchema,
      outputSchema:z.object({}).catchall(z.unknown()),
    },async args=>{
      try {
        const result=await tools[definition.name](args);
        return {content:[{type:'text',text:JSON.stringify(result,null,2)}],structuredContent:result};
      } catch(error) {
        return {isError:true,content:[{type:'text',text:`Error executing tool ${definition.name}: ${error instanceof Error?error.message:String(error)}`}]};
      }
    });
  }
  return server;
}
if(process.argv[1] && import.meta.url===pathToFileURL(realpathSync(process.argv[1])).href) await createServer().connect(new StdioServerTransport());

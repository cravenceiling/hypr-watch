import { createServer } from 'node:http';

// IPC keeps the bearer out of process arguments and temporary browser files.
process.once('message',token=>{
  if(typeof token!=='string') process.exit(1);
  const server=createServer((request,response)=>{
    if(request.url!=='/') {response.writeHead(404);response.end();return;}
    response.writeHead(302,{Location:'http://127.0.0.1:8766/#token='+token,'Cache-Control':'no-store'});response.end();
    clearTimeout(timer);server.close();
  });
  const timer=setTimeout(()=>server.close(),30000);
  server.listen(0,'127.0.0.1',()=>{
    const address=server.address();
    if(!address || typeof address==='string') process.exit(1);
    process.send?.(`http://127.0.0.1:${address.port}/`,()=>process.disconnect());
  });
});

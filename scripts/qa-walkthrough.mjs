import {QaBrowser} from './qa-cdp.mjs';
import fs from 'node:fs';
import {execFileSync} from 'node:child_process';
const b=await QaBrowser.connect('/tmp/reader-focused-20260910/state.json');
const path='evidence/focused-20260910/walkthrough.jsonl';
const adb=(...args)=>execFileSync('/Users/schober/Library/Android/sdk/platform-tools/adb',['-s','ZXKRS4VKGQ8PWGEQ',...args],{encoding:'utf8'});
try {
  const commands=JSON.parse(process.argv[2]);
  for(const command of commands) {
    let result;
    if(command.action==='back') {
      adb('shell','input','keyevent','4'); result=await b.android('ui',{action:'snapshot',name:command.name});
    } else if(command.action==='swipe') {
      adb('shell','input','swipe',...command.points.map(String));
      result=await b.android('ui',{action:'snapshot',name:command.name});
    } else if(command.action==='waitFor') result=await b.wait(async()=>{
      const r=await b.android('ui',{action:'snapshot'});
      return r.nodes.some(n=>n.text===command.label||n.description===command.label)?r:null;
    },15000);
    else result=await b.android('ui',command);
    fs.appendFileSync(path,JSON.stringify({at:new Date().toISOString(),command,result})+'\n');
    console.log(JSON.stringify(command===commands.at(-1)?result:{action:command.action,label:command.label,ok:true}));
  }
}catch(error){fs.appendFileSync(path,JSON.stringify({error:String(error)})+'\n');throw error;}finally{b.close();}

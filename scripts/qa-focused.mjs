import { QaBrowser } from './qa-cdp.mjs';
import fs from 'node:fs';
const root='/tmp/reader-focused-20260910';
const b=await QaBrowser.connect(root+'/state.json');
try {
  const mode=process.argv[2];
  const extension=(await b.send('Extensions.getExtensions')).extensions.find(x=>x.name==='Reader');
  if (!extension || !extension.enabled) throw Error('Reader unpacked extension unavailable');
  if (mode==='pair') {
    const page=await b.page(`chrome-extension://${extension.id}/src/ui/pairing.html`);
    const code=await b.wait(()=>b.evaluate(page.sessionId,'document.querySelector("#manual-code").value'),30000);
    const result=await b.android('pair',{request:code});
    console.log(JSON.stringify({paired:result.activeChannels===1,documents:result.documentCount}));
    fs.writeFileSync(root+'/browser-page.json',JSON.stringify({targetId:page.targetId,extensionId:extension.id}));
  } else if (mode==='ui') {
    console.log(JSON.stringify(await b.android('ui',JSON.parse(process.argv[3]))));
  } else if (mode==='snapshot') {
    const result=await b.android('snapshot');
    fs.writeFileSync('evidence/focused-20260910/intake-snapshot.json',JSON.stringify(result,null,2));
    console.log(JSON.stringify({documents:result.documentCount,captures:result.captures?.length,states:result.captures?.reduce((a,x)=>(a[x.status]=(a[x.status]??0)+1,a),{})}));
  } else if (mode==='intake' || mode==='retryChrome') {
    let corpus=JSON.parse(fs.readFileSync('evidence/focused-20260910/corpus.json'));
    const prior=mode==='retryChrome'?JSON.parse(fs.readFileSync('evidence/focused-20260910/intake-attempts.json')):[];
    const results=prior.filter(x=>!x.error);
    if(mode==='retryChrome') {fs.copyFileSync('evidence/focused-20260910/intake-attempts.json','evidence/focused-20260910/browser-readiness-rejected.json');corpus=corpus.filter(x=>x.path==='chrome'&&prior.some(p=>p.id===x.id&&p.error));}
    const control=await b.page(`chrome-extension://${extension.id}/src/ui/popup.html`);
    const status=()=>b.evaluate(control.sessionId,'chrome.runtime.sendMessage({kind:"reader-status"})');
    if(!(await status()).paired)throw Error('QA Chrome must be paired first');
    for(const article of corpus) {
      try {
        if(article.path==='chrome') {
          const existing=(await b.targets()).find(x=>x.type==='page'&&x.url===article.url);
          const page=existing?{targetId:existing.targetId,sessionId:await b.attach(existing.targetId)}:await b.page(article.url);
          const identity=await b.evaluate(page.sessionId,'({title:document.title,url:location.href,characters:document.body.innerText.length})');
          if(identity.characters<200)throw Error('Source did not load readable article text');
          const existingReceipt=(await status()).recent.find(x=>x.sourceUrl===identity.url||x.sourceUrl===article.url);
          if(!existingReceipt) {
            const tab=(await b.send('Target.getTargets',{filter:[{type:'tab',exclude:false},{exclude:true}]})).targetInfos.find(x=>x.url===identity.url);
            if(!tab)throw Error('QA tab target unavailable');
            await b.send('Extensions.triggerAction',{id:extension.id,targetId:tab.targetId});
          }
          const receipt=await b.wait(async()=>{
            const state=await status();
            const item=state.recent.find(x=>x.sourceUrl===identity.url||x.sourceUrl===article.url);
            if(item)return item;
            // Low-confidence extraction asks for a real, trusted confirmation.
            const tree=await b.send('Accessibility.getFullAXTree',{},page.sessionId);
            const save=tree.nodes.find(x=>x.role?.value==='button'&&x.name?.value==='Save extracted article');
            if(save?.backendDOMNodeId) {
              const box=await b.send('DOM.getBoxModel',{backendNodeId:save.backendDOMNodeId},page.sessionId);
              const p=box.model.border; const x=(p[0]+p[4])/2,y=(p[1]+p[5])/2;
              await b.send('Input.dispatchMouseEvent',{type:'mousePressed',x,y,button:'left',clickCount:1},page.sessionId);
              await b.send('Input.dispatchMouseEvent',{type:'mouseReleased',x,y,button:'left',clickCount:1},page.sessionId);
            }
            return null;
          },25000);
          results.push({...article,source:identity,...receipt});
          if(['chrome-1','chrome-2'].includes(article.id))await b.screenshot(page.sessionId,`evidence/focused-20260910/${article.id}.png`);
          await b.send('Target.closeTarget',{targetId:page.targetId});
        } else {
          const saved=await b.android('saveLink',{url:article.url});
          results.push({...article,submitted:saved.submitted});
        }
      } catch(error) { results.push({...article,error:String(error)}); if(String(error).includes('Action can only')||String(error).includes('QA tab target'))throw error; }
      fs.writeFileSync('evidence/focused-20260910/intake-attempts.json',JSON.stringify(results,null,2));
      console.log(JSON.stringify({attempted:results.length,id:article.id,result:results.at(-1).error??(article.path==='chrome'?'extension-captured':'android-submitted')}));
    }
    await b.evaluate(control.sessionId,'chrome.runtime.sendMessage({kind:"reader-check-acks"})');
    fs.writeFileSync('evidence/focused-20260910/chrome-status.json',JSON.stringify(await status(),null,2));
    console.log(JSON.stringify({completed:results.length,errors:results.filter(x=>x.error).length}));
  } else if(mode==='stop')console.log(JSON.stringify(await b.android('stop')));
  else throw Error('Unknown focused QA mode');
} finally {b.close();}

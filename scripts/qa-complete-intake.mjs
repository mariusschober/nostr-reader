import {QaBrowser} from './qa-cdp.mjs';
import fs from 'node:fs';
const b=await QaBrowser.connect('/tmp/reader-focused-20260910/state.json');
try {
  const corpus=JSON.parse(fs.readFileSync('evidence/focused-20260910/corpus.json'));
  let snapshot=await b.android('snapshot');
  const missing=corpus.filter(a=>a.path==='android-link'&&!snapshot.captures.some(c=>c.url===a.url));
  for(const article of missing) {
    await b.android('saveLink',{url:article.url});
    await b.wait(async()=>{snapshot=await b.android('snapshot');return snapshot.captures.some(c=>c.url===article.url);},15000);
    console.log(JSON.stringify({id:article.id,durablyAccepted:true}));
  }
  await b.wait(async()=>{snapshot=await b.android('snapshot');return new Set(snapshot.captures.map(c=>c.url)).size===50&&snapshot.captures.every(c=>!['pending','fetching'].includes(c.status));},90000);
  const target=(await b.targets()).find(t=>t.url.includes('/src/ui/popup.html'));
  const session=await b.attach(target.targetId);
  const chrome=await b.evaluate(session,'chrome.runtime.sendMessage({kind:"reader-status"})');
  const records=corpus.map(article=>{
    const documents=snapshot.library.filter(d=>d.url===article.url);
    const capture=snapshot.captures.find(c=>c.url===article.url);
    const receipt=chrome.recent.find(c=>c.sourceUrl===article.url);
    return {...article,documentCount:documents.length,document:documents[0]??null,capture:capture??null,captureAttempts:snapshot.captures.filter(c=>c.url===article.url).length,receipt:receipt??null};
  });
  const hashes=await b.android('snapshot',{documentIds:snapshot.library.map(d=>d.id),transferIds:chrome.recent.map(r=>r.transferId)});
  fs.writeFileSync('evidence/focused-20260910/intake-final.json',JSON.stringify({records,hashes:hashes.documents,acks:hashes.acks},null,2));
  fs.writeFileSync('evidence/focused-20260910/intake-snapshot.json',JSON.stringify(snapshot,null,2));
  fs.writeFileSync('evidence/focused-20260910/chrome-status.json',JSON.stringify({paired:chrome.paired,pending:chrome.pending,delivery:chrome.delivery,recent:chrome.recent},null,2));
  console.log(JSON.stringify({distinct:records.filter(r=>r.documentCount===1).length,chromeDelivered:records.filter(r=>r.path==='chrome'&&r.receipt?.state==='delivered').length,linksComplete:records.filter(r=>r.capture?.status==='completed').length,linkOnly:records.filter(r=>r.capture?.status==='link_only').length,missing:records.filter(r=>r.documentCount!==1).map(r=>r.id)}));
}finally{b.close();}

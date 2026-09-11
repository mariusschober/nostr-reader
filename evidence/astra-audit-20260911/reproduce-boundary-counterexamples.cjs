const fs=require('fs');const {createRequire}=require('module'); const vm=require('vm');
const path=require('path'); const repo=path.resolve(__dirname,'../..'); const req=createRequire(path.join(repo,'chrome/package.json'));
const ts=req('typescript');const {JSDOM}=req('jsdom');
const src=fs.readFileSync(path.join(repo,'chrome/src/extraction/pipeline.ts'),'utf8');
const actual=src.slice(src.indexOf('const INLINE_TAG_SELECTOR'),src.indexOf('function sanitizeHtml'));
const sandbox={exports:{}};vm.runInNewContext(ts.transpileModule(actual,{compilerOptions:{module:ts.ModuleKind.CommonJS,target:ts.ScriptTarget.ES2022}}).outputText,sandbox);
const {collectVisualBlockClasses,separateVisuallyBlockInline}=sandbox.exports;
for(const [name,html] of [
 ['shared_class_inline','<style>.legend .shared{display:block} p .shared{display:inline}</style><div class="legend"><span class="shared">Legend</span></div><p>micro<span class="shared">scope</span>s</p>'],
 ['classless_inline_style','<p><span style="display:block">Knowledge workers</span><span style="display:block">All other workers</span></p>']
]) {const dom=new JSDOM(html);const live=dom.window.document;const before=live.body.textContent;const classes=collectVisualBlockClasses(live);const clone=live.cloneNode(true);const inserted=separateVisuallyBlockInline(clone,el=>[...el.classList].some(c=>classes.has(c)));console.log(JSON.stringify({name,classes:[...classes],before,after:clone.body.textContent,inserted,liveUnchanged:live.body.textContent===before}));}

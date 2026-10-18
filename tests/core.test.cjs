const test=require('node:test'),assert=require('node:assert/strict'),fs=require('node:fs'),vm=require('node:vm');
const core=require('../reader-core.js');
test('content identity differs for equal names and sizes and survives renaming',async()=>{
  const a=new TextEncoder().encode('DOCUMENT A').buffer,b=new TextEncoder().encode('DOCUMENT B').buffer;
  assert.equal(a.byteLength,b.byteLength);assert.notEqual(await core.documentKey(a),await core.documentKey(b));
  assert.equal(await core.documentKey(a),await core.documentKey(a.slice(0)));
});
test('cross-span text matches use offsets in one searchable text model',()=>{
  const index=core.textIndex([{str:'alpha'},{str:'beta'},{str:'alpha'}]);
  assert.deepEqual(core.findMatches(index.text,'ALPHA BETA'),[{start:0,end:10}]);
  assert.deepEqual(index.spans,[{start:0,end:5},{start:6,end:10},{start:11,end:16}]);
  assert.equal(core.findMatches(index.text,'').length,0);
});
test('old generations cannot publish after new work or cancellation',()=>{
  const work=new core.Generation(),a=work.next(),b=work.next();assert.equal(work.isCurrent(a),false);assert.equal(work.isCurrent(b),true);
  work.next();assert.equal(work.isCurrent(b),false);
});
test('fit-page and bitmap limits apply to every page dimension',()=>{
  for(const [w,h] of [[595,842],[842,595],[300,1500]]){
    const scale=core.renderScale(w,h,1280,580,'page',1);
    assert.ok(w*scale<=1280+.001);assert.ok(h*scale<=580+.001);
    const x=w*30,y=h*30,ratio=core.pixelRatio(x,y,2.5);
    assert.ok(x*y*ratio*ratio<=16*1024*1024+.1);assert.ok(Math.max(x,y)*ratio<=8192+.01);
  }
});
test('PDF security options and vendored glyph code prohibit dynamic compilation',()=>{
  assert.equal(core.PDF_OPTIONS.isEvalSupported,false);assert.equal(core.PDF_OPTIONS.enableScripting,false);
  const engine=fs.readFileSync(require.resolve('../pdf.min.js'),'utf8');
  assert.ok(!engine.includes('new Function("c","size"'));
  const html=fs.readFileSync(require.resolve('../index.html'),'utf8');
  assert.ok(html.includes('Content-Security-Policy'));assert.ok(!html.includes("'unsafe-eval'"));
});
test('SW activation preserves other applications and legacy caches',async()=>{
  const handlers={},deleted=[],scope='https://ldjkorea.github.io/DJS-PDF/';let wait,current;
  const context=vm.createContext({
    self:{registration:{scope},addEventListener:(n,fn)=>handlers[n]=fn,clients:{claim:async()=>{}}},
    caches:{keys:async()=>['other-app-cache','pdf-reader-v4','djs-pdf-app:old',current],delete:async n=>deleted.push(n)}
  });
  vm.runInContext(fs.readFileSync(require.resolve('../sw.js'),'utf8'),context);current=vm.runInContext('CACHE_NAME',context);
  handlers.activate({waitUntil:p=>wait=p});await wait;assert.deepEqual(deleted,['djs-pdf-app:old']);
});
test('overlapping real searches publish only the final query results',async()=>{
  const source=fs.readFileSync(require.resolve('../app.js'),'utf8');
  const search=source.slice(source.indexOf('async function performSearch(query){'),source.indexOf('async function goToMatch(index){'));
  const context=vm.createContext({ReaderCore:core,setTimeout});
  vm.runInContext(`let searchQuery='',searchMatches=[],curSearchMatchIdx=-1;
    const pdfDoc={numPages:1},pageNum=1,searchCount={textContent:''},searchGeneration=new ReaderCore.Generation();
    function refreshHighlights(){} function clearSearchHighlights(){} function toast(){}
    async function getPageText(){await new Promise(r=>setTimeout(r,10));return {fullText:'ALPHA BETA'};}
    async function goToMatch(i){curSearchMatchIdx=i;}
    ${search}
    globalThis.run=async()=>{await Promise.all([performSearch('ALPHA'),performSearch('BETA')]);return {query:searchQuery,count:searchMatches.length};};`,context);
  const result=await context.run();assert.equal(result.query,'BETA');assert.equal(result.count,1);
});

const test=require('node:test'),assert=require('node:assert/strict'),fs=require('node:fs'),vm=require('node:vm');

function worker(offline=false){
  const handlers={},reader={body:'cached reader'},page={body:'network update page'},error={type:'error'};
  vm.runInNewContext(fs.readFileSync(require.resolve('../sw.js'),'utf8'),{
    URL,Response:{error:()=>error},
    self:{registration:{scope:'https://ldjkorea.github.io/DJS-PDF/'},addEventListener:(name,fn)=>handlers[name]=fn},
    caches:{open:async()=>({match:async key=>key==='./index.html'?reader:undefined})},
    fetch:async()=>{if(offline)throw Error('offline');return page;}
  });
  return {reader,page,error,async navigate(path){
    let result;handlers.fetch({request:{url:'https://ldjkorea.github.io/DJS-PDF/'+path,method:'GET',mode:'navigate'},respondWith:p=>result=p});
    return result;
  }};
}

test('update page navigation is not replaced by the cached PDF reader',async()=>{
  const w=worker();
  assert.equal(await w.navigate('update.html'),w.page);
  assert.equal(await w.navigate(''),w.reader);
  assert.equal(await w.navigate('index.html?source=home'),w.reader);
});

test('offline reader remains available without pretending to show an update page',async()=>{
  const w=worker(true);
  assert.equal(await w.navigate(''),w.reader);
  assert.equal(await w.navigate('update.html'),w.error);
});

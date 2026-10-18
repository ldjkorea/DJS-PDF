const test=require('node:test'),assert=require('node:assert/strict'),fs=require('node:fs'),path=require('node:path');
const pdfjs=require('../pdf.min.js'),{PDF_OPTIONS}=require('../reader-core.js');
pdfjs.GlobalWorkerOptions.workerSrc=path.resolve(__dirname,'../pdf.worker.min.js');
const bytes=()=>new Uint8Array(fs.readFileSync(path.join(__dirname,'fixtures/encrypted.pdf')));
test('password task callback supports incorrect password followed by successful retry',async()=>{
  const task=pdfjs.getDocument({data:bytes(),...PDF_OPTIONS}),reasons=[];
  task.onPassword=(update,reason)=>{reasons.push(reason);update(reason===1?'wrong':'audit123');};
  try{const doc=await task.promise;assert.equal(doc.numPages,12);assert.deepEqual(reasons,[1,2]);}
  finally{await task.destroy();}
});
test('password cancellation terminates pending document load',async()=>{
  const task=pdfjs.getDocument({data:bytes(),...PDF_OPTIONS});
  task.onPassword=()=>{setTimeout(()=>task.destroy(),0);};
  await assert.rejects(task.promise);await task.destroy();
});
test('invalid PDF fails before adoption',async()=>{
  const task=pdfjs.getDocument({data:new TextEncoder().encode('Not a PDF'),...PDF_OPTIONS});
  try{await assert.rejects(task.promise,{name:'InvalidPDFException'});}finally{await task.destroy();}
});

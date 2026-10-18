const test=require('node:test'),assert=require('node:assert/strict');
const {IDBFactory}=require('fake-indexeddb'),{create}=require('../reader-store.js');
test('one transaction persists matching document bytes and metadata',async()=>{
  const store=create(new IDBFactory()),bytes=new Uint8Array([1,2,3]).buffer;
  assert.equal(await store.idbSaveDocument({key:'sha256:a',page:2},bytes),true);
  assert.deepEqual(new Uint8Array(await store.idbGetFile('sha256:a')),new Uint8Array(bytes));
  assert.equal((await store.idbGetMeta('sha256:a')).page,2);
  (await store.openDB()).close();
});
test('a metadata failure rolls back the file write instead of leaving an orphan',async()=>{
  const store=create(new IDBFactory()),db=await store.openDB(),transaction=db.transaction.bind(db);
  db.transaction=(names,mode)=>{
    const tx=transaction(names,mode);
    if(mode==='readwrite'&&Array.isArray(names)&&names.includes('meta')){
      const objectStore=tx.objectStore.bind(tx);
      tx.objectStore=name=>{const s=objectStore(name);if(name==='meta')s.put=()=>{tx.abort();throw new DOMException('quota','QuotaExceededError');};return s;};
    }return tx;
  };
  assert.equal(await store.idbSaveDocument({key:'failure'},new ArrayBuffer(3)),false);
  await new Promise(r=>setTimeout(r,0));
  assert.equal(await store.idbGetFile('failure'),null);assert.equal(await store.idbGetMeta('failure'),undefined);db.close();
});
test('legacy migration hides duplicates while retaining recovery bytes and bookmarks',async()=>{
  const store=create(new IDBFactory()),old='pdf::same.pdf::3',bytes=new Uint8Array([1,2,3]).buffer;
  const meta={key:old,page:3,pinned:true,bookmarks:[{page:2}]};
  await store.idbSaveDocument(meta,bytes);
  await store.idbSaveDocument({...meta,key:'sha256:new',legacyKeys:[old]},bytes);
  assert.deepEqual((await store.idbGetAllMeta()).map(m=>m.key),['sha256:new']);
  assert.deepEqual((await store.idbGetMeta('sha256:new')).bookmarks,[{page:2}]);
  assert.ok(await store.idbGetFile(old));assert.ok(await store.idbGetMeta(old));(await store.openDB()).close();
});
test('database connections are reused and reopened after version changes',async()=>{
  const store=create(new IDBFactory()),a=await store.openDB();assert.equal(await store.openDB(),a);
  a.onversionchange();const b=await store.openDB();assert.notEqual(a,b);b.close();
});

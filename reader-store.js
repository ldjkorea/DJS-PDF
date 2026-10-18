(function(root){
'use strict';
function create(indexedDB,name='DjsPdfDB_v2'){
const DB_NAME = name;
const DB_VERSION = 1;
let dbPromise=null;
function openDB(){
  if(dbPromise) return dbPromise;
  dbPromise=new Promise((resolve,reject)=>{
    const req=indexedDB.open(DB_NAME,DB_VERSION);
    req.onupgradeneeded=()=>{
      const db=req.result;
      if(!db.objectStoreNames.contains('meta')) db.createObjectStore('meta',{keyPath:'key'});
      if(!db.objectStoreNames.contains('files')) db.createObjectStore('files',{keyPath:'key'});
    };
    req.onsuccess=()=>{const db=req.result;db.onversionchange=()=>{db.close();dbPromise=null;};resolve(db);};
    req.onerror=()=>{dbPromise=null;reject(req.error);};
    req.onblocked=()=>{dbPromise=null;reject(new Error('다른 앱 창을 닫고 다시 시도해 주세요'));};
  });
  return dbPromise;
}
function txDone(tx){
  return new Promise(resolve=>{tx.oncomplete=()=>resolve(true);tx.onerror=tx.onabort=()=>resolve(false);});
}
async function idbGet(store,key){
  try{
    const db=await openDB(),tx=db.transaction(store,'readonly');
    return await new Promise((resolve,reject)=>{
      const req=key===undefined?tx.objectStore(store).getAll():tx.objectStore(store).get(key);
      req.onsuccess=()=>resolve(req.result);req.onerror=()=>reject(req.error);tx.onabort=()=>reject(tx.error);
    });
  }catch(_){return key===undefined?[]:null;}
}
async function idbGetFile(key){const row=await idbGet('files',key);return row?row.data:null;}
async function idbGetMeta(key){return idbGet('meta',key);}
async function idbGetAllMeta(){
  const rows=await idbGet('meta');
  const migrated=new Set(rows.flatMap(row=>row.legacyKeys||[]));
  return rows.filter(row=>!migrated.has(row.key)).sort((a,b)=>(b.updatedAt||0)-(a.updatedAt||0));
}
async function idbSaveDocument(meta,buffer){
  try{
    const db=await openDB(),tx=db.transaction(['files','meta'],'readwrite'),done=txDone(tx);
    tx.objectStore('files').put({key:meta.key,data:buffer});tx.objectStore('meta').put(meta);
    return await done;
  }catch(_){return false;}
}
async function idbSaveMeta(meta){
  try{const db=await openDB(),tx=db.transaction('meta','readwrite'),done=txDone(tx);tx.objectStore('meta').put(meta);return await done;}
  catch(_){return false;}
}
async function idbDelete(key){
  try{
    const meta=await idbGetMeta(key),db=await openDB(),tx=db.transaction(['meta','files'],'readwrite'),done=txDone(tx);
    for(const k of [key,...(meta?.legacyKeys||[])]){tx.objectStore('meta').delete(k);tx.objectStore('files').delete(k);}
    return await done;
  }catch(_){return false;}
}
async function idbClearAll(){
  try{const db=await openDB(),tx=db.transaction(['meta','files'],'readwrite'),done=txDone(tx);tx.objectStore('meta').clear();tx.objectStore('files').clear();return await done;}
  catch(_){return false;}
}


return {openDB,idbGetFile,idbGetMeta,idbGetAllMeta,idbSaveDocument,idbSaveMeta,idbDelete,idbClearAll};
}
const api={create};if(typeof module==='object'&&module.exports)module.exports=api;root.ReaderStore=api;
})(globalThis);

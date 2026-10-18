(function(root){
  'use strict';
  const MAX_FILE_BYTES = 256 * 1024 * 1024;
  const PDF_OPTIONS = Object.freeze({ isEvalSupported:false, enableScripting:false });
  async function documentKey(buffer){
    if(!root.crypto || !root.crypto.subtle) throw new Error('안전한 문서 구분을 위해 최신 브라우저 또는 Android WebView가 필요합니다');
    const hash = await root.crypto.subtle.digest('SHA-256', buffer);
    return 'sha256:' + Array.from(new Uint8Array(hash), b=>b.toString(16).padStart(2,'0')).join('');
  }
  function textIndex(items){
    let text='';
    const spans=[];
    for(const item of items){
      if(typeof item.str !== 'string') continue;
      if(text && !/\s$/.test(text)) text+=' ';
      spans.push({start:text.length,end:text.length+item.str.length});
      text+=item.str;
    }
    return {text,spans};
  }
  function findMatches(text,query){
    const needle=query.toLowerCase();
    if(!needle) return [];
    const lower=text.toLowerCase(), matches=[];
    for(let i=lower.indexOf(needle);i!==-1;i=lower.indexOf(needle,i+needle.length)) matches.push({start:i,end:i+needle.length});
    return matches;
  }
  function renderScale(width,height,availableWidth,availableHeight,mode,zoom){
    const base=Math.max(1,availableWidth)/width;
    return mode==='page' ? Math.min(base,Math.max(1,availableHeight)/height) : base*zoom;
  }
  function pixelRatio(width,height,dpr){
    // Bound both area and dimension, including at high zoom on a desktop monitor.
    return Math.min(dpr,8192/Math.max(width,height),Math.sqrt(16*1024*1024/(width*height)));
  }
  class Generation {
    constructor(){this.value=0;}
    next(){return ++this.value;}
    isCurrent(value){return value===this.value;}
  }
  const api={MAX_FILE_BYTES,PDF_OPTIONS,documentKey,textIndex,findMatches,renderScale,pixelRatio,Generation};
  if(typeof module==='object' && module.exports) module.exports=api;
  root.ReaderCore=api;
})(globalThis);

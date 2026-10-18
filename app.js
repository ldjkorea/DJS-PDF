if (window.pdfjsLib) pdfjsLib.GlobalWorkerOptions.workerSrc = 'pdf.worker.min.js';

/* ── DOM 유틸 ── */
const $ = id => document.getElementById(id);
const welcome=$('welcome'), viewer=$('viewer'), wrap=$('canvasWrap'),
      canvas=$('pdfCanvas'), ctx=canvas.getContext('2d',{alpha:false}),
      textLayer=$('textLayer'),
      topbar=$('topbar'), bottombar=$('bottombar'), loader=$('loader'), loadMsg=$('loadMsg'),
      toastEl=$('toast'), fileInput=$('fileInput'),
      dropOverlay=$('dropOverlay'), pwdModal=$('pwdModal'),
      searchBar=$('searchBar'), searchInput=$('searchInput'), searchCount=$('searchCount'),
      tocDrawer=$('tocDrawer'), tocBackdrop=$('tocBackdrop'), tocList=$('tocList'),
      pageSlider=$('pageSlider'), scrubBubble=$('scrubBubble'), scrubMax=$('scrubMax'),
      continuousStage=$('continuousStage'),
      viewModeBtn=$('viewModeBtn'), bookmarkBtn=$('bookmarkBtn'),
      tabToc=$('tabToc'), tabBookmarks=$('tabBookmarks'), bookmarkList=$('bookmarkList'),
      bookmarkCount=$('bookmarkCount'), volNavToggle=$('volNavToggle'),
      fitBtnText=$('fitBtnText');

/* ── IndexedDB 저장소 (DjsPdfDB_v2) ── */
const {openDB,idbGetFile,idbGetMeta,idbGetAllMeta,idbSaveDocument,idbSaveMeta,idbDelete,idbClearAll}=ReaderStore.create(indexedDB);

/* ── 뷰어 상태 ── */
let pdfDoc=null, pageNum=1, totalPages=1;
let zoom=1, curRotation=0;
const MIN_ZOOM=0.5, MAX_ZOOM=6;
let baseScale=1, curViewport=null, docKey='', curName='';
let rendering=false, pendingPage=null;
let activeDocument=null, candidateTask=null, saveQueue=Promise.resolve(), storageWarningShown=false;
const loadGeneration=new ReaderCore.Generation(), renderGeneration=new ReaderCore.Generation();
let singleRenderTask=null, singleTextTask=null, singleRenderPromise=Promise.resolve(), continuousGeneration=0;
let continuousPages=[];
const APP_VERSION='1.2.1';
function readSetting(key,fallback){try{return localStorage.getItem(key)||fallback;}catch(_){return fallback;}}
const DPR=Math.min(window.devicePixelRatio||1, 2.5);

/* 보기 모드 (single: 단면 넘김, continuous: 연속 세로 스크롤) */
let viewMode = readSetting('djs_view_mode','single');
let curBookmarks = [];
let volNavEnabled = readSetting('djs_vol_nav','true') !== 'false';
let continuousObserver = null;
const renderedPagesMap = new Map();
let fitMode = 'width'; // 'width' | 'page'
let isContinuousInitializing = false;

/* 테마 필터 (default, dark, sepia, green) */
const FILTERS = ['filter-default', 'filter-dark', 'filter-sepia', 'filter-green'];
const FILTER_NAMES = { 'filter-default': '기본 모드', 'filter-dark': '다크 읽기 모드', 'filter-sepia': '세피아 모드', 'filter-green': '눈편한 모드' };
let curFilterIndex = 0;

function metaSnapshot(){
  return {...activeDocument.meta,key:docKey,name:curName,page:pageNum,totalPages,rotation:curRotation,
    filter:FILTERS[curFilterIndex],bookmarks:curBookmarks.map(b=>({...b})),updatedAt:Date.now()};
}
function warnStorage(){
  if(!storageWarningShown){storageWarningShown=true;toast('오프라인 저장에 실패했습니다. 원본 파일을 보관하고 저장 공간을 확인해 주세요.');}
}
function saveCurrentMeta(){
  if(!activeDocument) return Promise.resolve(false);
  const doc=activeDocument,meta=metaSnapshot();doc.meta=meta;
  saveQueue=saveQueue.catch(()=>false).then(async()=>{
    const ok=doc.persisted?await idbSaveMeta(meta):await idbSaveDocument(meta,doc.buffer);
    if(ok) doc.persisted=true;else warnStorage();return ok;
  });
  return saveQueue;
}

/* ── 화면 꺼짐 방지 (WakeLock & Native) ── */
let wakeLock = null;
async function requestWakeLock(){
  if(window.AndroidBridge && window.AndroidBridge.setKeepScreenOn){
    window.AndroidBridge.setKeepScreenOn(true);
  }
  if('wakeLock' in navigator){
    try{ wakeLock = await navigator.wakeLock.request('screen'); }catch(_){}
  }
}
function releaseWakeLock(){
  if(window.AndroidBridge && window.AndroidBridge.setKeepScreenOn){
    window.AndroidBridge.setKeepScreenOn(false);
  }
  if(wakeLock){
    wakeLock.release().catch(()=>{});
    wakeLock = null;
  }
}

/* ── 문서 공유 (Native Intent & Web Share) ── */
let sharing=false;
async function shareCurrentDoc(){
  if(!activeDocument||sharing) return;
  sharing=true;showLoader('공유 준비 중…');
  const doc=activeDocument;
  try{
    const bridge=window.AndroidBridge;
    if(bridge?.beginShare){
      if(!bridge.beginShare(doc.key.slice(7),doc.name,doc.buffer.byteLength)) throw new Error('공유 파일을 준비하지 못했습니다');
      const bytes=new Uint8Array(doc.buffer);
      for(let i=0;i<bytes.length;i+=49152){
        if(activeDocument!==doc) throw new Error('문서가 변경되었습니다. 다시 공유해 주세요');
        const chunk=bytes.subarray(i,i+49152);
        if(!bridge.appendShareChunk(btoa(String.fromCharCode(...chunk)))) throw new Error('공유 파일 저장 실패');
        await new Promise(r=>setTimeout(r,0));
      }
      if(!bridge.finishShare()) throw new Error('공유 파일 무결성 확인 실패');
    }else if(navigator.share){
      const file=new File([doc.buffer],doc.name,{type:'application/pdf'});
      if(navigator.canShare&&!navigator.canShare({files:[file]})) throw new Error('파일 공유를 지원하지 않는 브라우저입니다');
      hideLoader();await navigator.share({files:[file],title:doc.name});
    }else toast('현재 환경에서는 파일 공유를 지원하지 않습니다');
  }catch(err){window.AndroidBridge?.cancelShare?.();if(err.name!=='AbortError')toast('공유 실패: '+err.message);}
  finally{sharing=false;hideLoader();}
}

$('shareBtn').onclick = shareCurrentDoc;

/* ── 목차 (TOC) & 책갈피 드로어 ── */
function openTocDrawer(){
  renderBookmarksList();
  if(volNavToggle){
    volNavToggle.classList.toggle('active', volNavEnabled);
    volNavToggle.textContent = volNavEnabled ? 'ON' : 'OFF';
  }
  tocDrawer.classList.add('open');
  tocBackdrop.classList.add('open');
}
function closeTocDrawer(){
  tocDrawer.classList.remove('open');
  tocBackdrop.classList.remove('open');
}
$('tocBtn').onclick = openTocDrawer;
$('closeTocBtn').onclick = closeTocDrawer;
$('tocBackdrop').onclick = closeTocDrawer;

async function navigateToDest(dest){
  try{
    let explicitDest = dest;
    if(typeof dest === 'string'){
      explicitDest = await pdfDoc.getDestination(dest);
    }
    if(Array.isArray(explicitDest)){
      const destRef = explicitDest[0];
      const pageIndex = Number.isInteger(destRef)?destRef:await pdfDoc.getPageIndex(destRef);
      closeTocDrawer();
      gotoPage(pageIndex + 1);
    }
  }catch(err){
    console.warn('TOC dest navigation failed:', err);
  }
}

function buildTocTree(items, level){
  level = level || 0;
  if(level === 0) tocList.innerHTML = '';
  if(!items || !items.length){
    if(level === 0){
      tocList.innerHTML = '<div style="padding:24px;text-align:center;color:var(--muted);font-size:13px">목차가 없는 문서입니다.<br><span style="font-size:12px;color:var(--muted-soft)">상단 책갈피 탭에서 원하는 페이지를 저장할 수 있습니다.</span></div>';
    }
    return;
  }
  items.forEach(item => {
    const el = document.createElement('div');
    el.className = 'toc-item' + (level > 0 ? ' level-' + Math.min(level, 2) : '');
    const titleText = (item.title || '').trim();
    el.innerHTML = `<span class="toc-text">${escapeHtml(titleText)}</span>`;
    if(item.dest){
      el.onclick = () => navigateToDest(item.dest);
    }
    tocList.appendChild(el);
    if(item.items && item.items.length){
      buildTocTree(item.items, level + 1);
    }
  });
}

/* ── 사용자 책갈피 (Bookmarks) & 볼륨키 설정 ── */
function updateBookmarkButton(){
  if(!bookmarkBtn) return;
  const isBookmarked = curBookmarks.some(b => b.page === pageNum);
  bookmarkBtn.classList.toggle('active', isBookmarked);
}

function renderBookmarksList(){
  if(!bookmarkList) return;
  bookmarkCount.textContent = curBookmarks.length;
  bookmarkList.innerHTML = '';
  if(!curBookmarks.length){
    bookmarkList.innerHTML = '<div style="padding:24px;text-align:center;color:var(--muted);font-size:13px">등록된 책갈피가 없습니다.<br><span style="font-size:12px;color:var(--muted-soft)">상단 책갈피(🔖) 아이콘을 눌러 추가하세요.</span></div>';
    return;
  }
  curBookmarks.forEach((bm, i) => {
    const item = document.createElement('div');
    item.className = 'bookmark-item';
    item.innerHTML = `
      <div style="display:flex;align-items:center;gap:10px">
        <svg width="15" height="15" viewBox="0 0 24 24" fill="var(--accent)" stroke="var(--accent)" stroke-width="2"><path d="M19 21l-7-5-7 5V5a2 2 0 0 1 2-2h10a2 2 0 0 1 2 2z"/></svg>
        <span style="font-weight:600">${bm.page} 쪽</span>
        <span style="font-size:11.5px;color:var(--muted-soft)">${formatDate(bm.time)}</span>
      </div>
      <button class="bookmark-del" aria-label="책갈피 삭제" title="삭제">✕</button>
    `;
    item.onclick = (e) => {
      if(e.target.closest('.bookmark-del')) return;
      closeTocDrawer();
      gotoPage(bm.page);
    };
    item.querySelector('.bookmark-del').onclick = async (e) => {
      e.stopPropagation();
      curBookmarks.splice(i, 1);
      updateBookmarkButton();
      renderBookmarksList();
      await saveCurrentMeta();
      toast('책갈피를 삭제했습니다');
    };
    bookmarkList.appendChild(item);
  });
}

if(bookmarkBtn){
  bookmarkBtn.onclick = async () => {
    const idx = curBookmarks.findIndex(b => b.page === pageNum);
    if(idx >= 0){
      curBookmarks.splice(idx, 1);
      toast(pageNum + '쪽 책갈피를 삭제했습니다');
    } else {
      curBookmarks.push({ page: pageNum, time: Date.now() });
      curBookmarks.sort((a,b) => a.page - b.page);
      toast(pageNum + '쪽을 책갈피에 추가했습니다');
    }
    updateBookmarkButton();
    renderBookmarksList();
    await saveCurrentMeta();
  };
}

if(tabToc && tabBookmarks){
  tabToc.onclick = () => {
    tabToc.classList.add('active');
    tabBookmarks.classList.remove('active');
    tocList.style.display = 'block';
    bookmarkList.style.display = 'none';
  };
  tabBookmarks.onclick = () => {
    tabBookmarks.classList.add('active');
    tabToc.classList.remove('active');
    tocList.style.display = 'none';
    bookmarkList.style.display = 'block';
    renderBookmarksList();
  };
}

if(volNavToggle){
  volNavToggle.onclick = () => {
    volNavEnabled = !volNavEnabled;
    try{localStorage.setItem('djs_vol_nav',volNavEnabled);}catch(_){}
    volNavToggle.classList.toggle('active', volNavEnabled);
    volNavToggle.textContent = volNavEnabled ? 'ON' : 'OFF';
    if(window.AndroidBridge && window.AndroidBridge.setVolumeKeyNavEnabled){
      window.AndroidBridge.setVolumeKeyNavEnabled(volNavEnabled);
    }
    toast(volNavEnabled ? '볼륨키 페이지 넘김 활성화' : '볼륨키 페이지 넘김 꺼짐 (기기 볼륨 조절)');
  };
  volNavToggle.classList.toggle('active', volNavEnabled);
  volNavToggle.textContent = volNavEnabled ? 'ON' : 'OFF';
  if(window.AndroidBridge && window.AndroidBridge.setVolumeKeyNavEnabled){
    window.AndroidBridge.setVolumeKeyNavEnabled(volNavEnabled);
  }
}

/* ── 본문 검색 & 실시간 하이라이트 ── */
let searchQuery = '';
let searchMatches = [];
let curSearchMatchIdx = -1;
const pageTextCache = new Map();
let searchDebounceTimer = null;
const searchGeneration=new ReaderCore.Generation();

function toggleSearchBar(force){
  const isHidden = searchBar.classList.contains('hide');
  const shouldShow = force !== undefined ? force : isHidden;
  searchBar.classList.toggle('hide', !shouldShow);
  $('searchBtn').classList.toggle('active', shouldShow);
  if(shouldShow){
    setTimeout(()=>{ searchInput.focus(); searchInput.select(); }, 120);
  } else {
    clearSearchHighlights();
  }
}
$('searchBtn').onclick = () => toggleSearchBar();
$('searchCloseBtn').onclick = () => toggleSearchBar(false);

function clearLayerMarks(layer){
  layer.querySelectorAll('mark').forEach(mark=>mark.replaceWith(document.createTextNode(mark.textContent)));
  layer.normalize();
}
function clearSearchHighlights(){
  searchGeneration.next();clearTimeout(searchDebounceTimer);searchQuery='';searchMatches=[];curSearchMatchIdx=-1;
  searchCount.textContent='0 / 0';
  clearLayerMarks(textLayer);continuousStage.querySelectorAll('.textLayer').forEach(clearLayerMarks);
}
async function getPageText(num,doc=pdfDoc){
  if(doc===pdfDoc&&pageTextCache.has(num))return pageTextCache.get(num);
  const page=await doc.getPage(num),tc=await page.getTextContent(),index=ReaderCore.textIndex(tc.items);
  const result={tc,fullText:index.text};if(doc===pdfDoc)pageTextCache.set(num,result);return result;
}
async function performSearch(query){
  query=(query||'').trim();
  if(!query||!pdfDoc){clearSearchHighlights();return;}
  const token=searchGeneration.next(),doc=pdfDoc,count=doc.numPages;
  const matches=[];searchQuery=query;searchMatches=[];curSearchMatchIdx=-1;refreshHighlights();searchCount.textContent='검색 중…';
  try{
    for(let i=1;i<=count;i++){
      const {fullText}=await getPageText(i,doc);
      if(!searchGeneration.isCurrent(token)||pdfDoc!==doc)return;
      ReaderCore.findMatches(fullText,query).forEach((match,matchIndex)=>matches.push({...match,pageNum:i,matchIndex}));
    }
    if(!searchGeneration.isCurrent(token)||pdfDoc!==doc)return;
    searchMatches=matches;
    if(!matches.length){searchCount.textContent='0 / 0';refreshHighlights();toast('일치하는 결과가 없습니다');return;}
    const target=matches.findIndex(m=>m.pageNum>=pageNum);await goToMatch(target<0?0:target);
  }catch(err){if(searchGeneration.isCurrent(token)){searchMatches=[];refreshHighlights();searchCount.textContent='검색 실패';toast('본문 검색을 완료하지 못했습니다');}}
}
async function goToMatch(index){
  if(!searchMatches.length)return;
  const token=searchGeneration.value;
  curSearchMatchIdx=(index+searchMatches.length)%searchMatches.length;
  const match=searchMatches[curSearchMatchIdx];searchCount.textContent=`${curSearchMatchIdx+1} / ${searchMatches.length}`;
  if(match.pageNum!==pageNum||viewMode==='continuous')await gotoPage(match.pageNum);
  if(!searchGeneration.isCurrent(token))return;
  if(viewMode==='continuous')await renderContinuousPage(match.pageNum);
  if(!searchGeneration.isCurrent(token))return;
  refreshHighlights();
  const layer=viewMode==='continuous'?$('scrollTextLayer_'+match.pageNum):textLayer;
  layer?.querySelector('mark.active')?.scrollIntoView({behavior:'smooth',block:'center',inline:'center'});
}

$('searchNextBtn').onclick = () => {
  if(searchMatches.length) goToMatch(curSearchMatchIdx + 1);
};
$('searchPrevBtn').onclick = () => {
  if(searchMatches.length) goToMatch(curSearchMatchIdx - 1);
};

searchInput.addEventListener('input', e => {
  searchGeneration.next();clearTimeout(searchDebounceTimer);
  const val = e.target.value;
  searchDebounceTimer = setTimeout(() => performSearch(val), 280);
});
searchInput.addEventListener('keydown', e => {
  if(e.key === 'Enter'){
    e.preventDefault();
    if(e.shiftKey){
      $('searchPrevBtn').click();
    } else {
      $('searchNextBtn').click();
    }
  } else if(e.key === 'Escape'){
    toggleSearchBar(false);
  }
});

function highlightLayer(layer,p){
  clearLayerMarks(layer);
  if(!searchQuery)return;
  const spans=Array.from(layer.querySelectorAll('span')).filter(el=>!el.querySelector('span'));
  const index=ReaderCore.textIndex(spans.map(el=>({str:el.textContent})));
  const matches=searchMatches.map((m,i)=>({...m,index:i})).filter(m=>m.pageNum===p);
  spans.forEach((span,i)=>{
    const bounds=index.spans[i],text=span.textContent;let cursor=0,html='';
    for(const match of matches){
      const start=Math.max(match.start,bounds.start)-bounds.start,end=Math.min(match.end,bounds.end)-bounds.start;
      if(start>=end)continue;
      html+=escapeHtml(text.slice(cursor,start));
      html+=`<mark class="search-match${match.index===curSearchMatchIdx?' active':''}">${escapeHtml(text.slice(start,end))}</mark>`;cursor=end;
    }
    html+=escapeHtml(text.slice(cursor));span.innerHTML=html;
  });
}
function highlightTextLayer(){highlightLayer(textLayer,pageNum);}
function refreshHighlights(){
  highlightTextLayer();continuousStage.querySelectorAll('.textLayer').forEach(layer=>highlightLayer(layer,Number(layer.dataset.page)));
}

/* ── 하단 페이지 스크러버 제어 ── */
pageSlider.addEventListener('input', e => {
  const val = parseInt(e.target.value) || 1;
  scrubBubble.textContent = `${val} / ${totalPages} 쪽`;
  scrubBubble.classList.add('visible');
  $('pageInput').value = val;
});
pageSlider.addEventListener('change', e => {
  scrubBubble.classList.remove('visible');
  const val = parseInt(e.target.value) || 1;
  gotoPage(val);
});

/* ── 파일 열기 처리 ── */
function pickFile(){
  if (window.AndroidBridge && window.AndroidBridge.pickFile) {
    window.AndroidBridge.pickFile();
  } else {
    fileInput.value='';
    fileInput.click();
  }
}
$('openMain').onclick=pickFile;
$('openBtn2').onclick=pickFile;

fileInput.addEventListener('change', e=>{
  const f=e.target.files&&e.target.files[0];
  if(f) openFile(f);
});

function beginOpen(){
  const token=loadGeneration.next();
  if(candidateTask){candidateTask.destroy().catch(()=>{});candidateTask=null;}
  passwordCallback=null;pwdModal.classList.remove('on');showLoader('PDF 불러오는 중…');return token;
}
async function openFile(file){
  const token=beginOpen();
  try{
    if(file.size>ReaderCore.MAX_FILE_BYTES) throw new Error('256MB 이하 PDF를 열어 주세요');
    const buffer=await file.arrayBuffer();
    if(loadGeneration.isCurrent(token)) await loadDoc(buffer,file.name||'문서.pdf',{token});
  }catch(err){if(loadGeneration.isCurrent(token)){hideLoader();toast(err.message||'파일을 읽지 못했습니다');}}
}
let passwordCallback=null;
function handlePassword(callback,reason,token){
  if(!loadGeneration.isCurrent(token)) return;
  hideLoader();passwordCallback=callback;$('pwdInput').value='';
  $('pwdMsg').textContent=reason===2?'비밀번호가 올바르지 않습니다. 다시 입력해 주세요.':'이 문서는 암호화되어 있습니다. 비밀번호를 입력해 주세요.';
  pwdModal.classList.add('on');setTimeout(()=>$('pwdInput').focus(),100);
}
$('pwdSubmitBtn').onclick=()=>{
  const pwd=$('pwdInput').value;if(!pwd||!passwordCallback)return;
  pwdModal.classList.remove('on');showLoader('비밀번호 확인 중…');const cb=passwordCallback;passwordCallback=null;cb(pwd);
};
function cancelPassword(){beginOpen();hideLoader();toast('열기를 취소했습니다');}
$('pwdCancelBtn').onclick=cancelPassword;
$('pwdInput').addEventListener('keydown',e=>{if(e.key==='Enter')$('pwdSubmitBtn').click();if(e.key==='Escape')cancelPassword();});
async function stopRendering(){
  renderGeneration.next();continuousGeneration++;
  if(continuousObserver){continuousObserver.disconnect();continuousObserver=null;}
  if(singleRenderTask)singleRenderTask.cancel();singleTextTask?.cancel();
  const tasks=[singleRenderPromise.catch(()=>{})];
  for(const state of renderedPagesMap.values()){state.task?.cancel();state.textTask?.cancel();if(state.promise)tasks.push(state.promise.catch(()=>{}));}
  await Promise.all(tasks);renderedPagesMap.clear();continuousStage.replaceChildren();
}
async function loadDoc(buffer,name,options={}){
  const token=options.token||beginOpen();let task=null,adopted=false;
  try{
    if(!window.pdfjsLib)throw new Error('PDF 엔진 로드 실패');
    if(buffer.byteLength>ReaderCore.MAX_FILE_BYTES)throw new Error('256MB 이하 PDF를 열어 주세요');
    const key=await ReaderCore.documentKey(buffer);
    if(!loadGeneration.isCurrent(token))return false;
    await saveQueue;
    let meta=await idbGetMeta(key),legacyKey=options.legacyKey;
    if(!meta){
      const oldKey=legacyKey||'pdf::'+name+'::'+buffer.byteLength;
      const oldBytes=await idbGetFile(oldKey);
      if(oldBytes&&(await ReaderCore.documentKey(oldBytes))===key){meta=await idbGetMeta(oldKey);legacyKey=oldKey;}
    }
    if(!loadGeneration.isCurrent(token))return false;
    task=pdfjsLib.getDocument({data:new Uint8Array(buffer.slice(0)),...ReaderCore.PDF_OPTIONS});candidateTask=task;
    task.onPassword=(cb,reason)=>handlePassword(cb,reason,token);
    const candidate=await task.promise;
    const firstPage=Math.max(1,Math.min(candidate.numPages,Number(meta?.page)||1));
    await candidate.getPage(firstPage);
    let outline=null;try{outline=await candidate.getOutline();}catch(_){}
    if(!loadGeneration.isCurrent(token))return false;
    const nextMeta={...meta,key,name,page:firstPage,totalPages:candidate.numPages,rotation:Number(meta?.rotation)||0,
      filter:FILTERS.includes(meta?.filter)?meta.filter:FILTERS[0],
      bookmarks:(Array.isArray(meta?.bookmarks)?meta.bookmarks:[]).filter(b=>Number.isInteger(b.page)&&b.page>0&&b.page<=candidate.numPages),
      legacyKeys:[...new Set([...(Array.isArray(meta?.legacyKeys)?meta.legacyKeys:[]),...(legacyKey?[legacyKey]:[])])],updatedAt:Date.now()};
    const persisted=await idbSaveDocument(nextMeta,buffer);
    if(!loadGeneration.isCurrent(token))return false;
    await saveQueue;await stopRendering();
    if(!loadGeneration.isCurrent(token))return false;
    const previous=activeDocument;
    activeDocument={key,name,buffer,meta:nextMeta,persisted,task};adopted=true;candidateTask=null;
    pdfDoc=candidate;docKey=key;curName=name;totalPages=candidate.numPages;pageNum=firstPage;curRotation=nextMeta.rotation;
    curBookmarks=nextMeta.bookmarks.map(b=>({...b}));curFilterIndex=FILTERS.indexOf(nextMeta.filter);zoom=1;
    pageTextCache.clear();clearSearchHighlights();toggleSearchBar(false);searchInput.value='';closeTocDrawer();
    $('pageTotal').textContent=totalPages;$('pageInput').max=totalPages;pageSlider.max=totalPages;scrubMax.textContent=totalPages;
    $('fileName').textContent=curName.replace(/\.pdf$/i,'');$('fileName').title=curName;
    welcome.style.display='none';viewer.classList.add('on');topbar.classList.remove('hide');bottombar.classList.remove('hide');
    buildTocTree(outline);applyFilterClass(nextMeta.filter,false);updateBookmarkButton();renderBookmarksList();updateViewModeUI();syncChromeInsets();
    requestWakeLock();window.AndroidBridge?.setViewerActive?.(true);
    previous?.task.destroy().catch(()=>{});
    if(viewMode==='continuous'){$('stage').style.display='none';continuousStage.style.display='flex';await initContinuousStage();}
    else{continuousStage.style.display='none';$('stage').style.display='flex';await renderPage(pageNum,true);}
    if(loadGeneration.isCurrent(token)){hideLoader();if(!persisted){storageWarningShown=false;warnStorage();}else if(pageNum>1)toast(pageNum+'페이지부터 이어보기');}
    return true;
  }catch(err){
    if(loadGeneration.isCurrent(token)){pwdModal.classList.remove('on');hideLoader();toast(err.name==='InvalidPDFException'?'열 수 없거나 손상된 PDF입니다':err.message||'PDF 열기 실패');}
    return false;
  }finally{
    if(task&&!adopted)await task.destroy().catch(()=>{});
    if(candidateTask===task)candidateTask=null;
  }
}

/* ── 페이지 렌더링 (Canvas + TextLayer) ── */
function syncChromeInsets(){
  const top=topbar.offsetHeight+8,bottom=bottombar.offsetHeight+8;
  document.documentElement.style.setProperty('--reader-top',top+'px');
  document.documentElement.style.setProperty('--reader-bottom',bottom+'px');
  return {top,bottom};
}
function updatePageUI(n){
  pageNum=n;window.pageNum=n;window.totalPages=totalPages;
  $('pageInput').value=n;pageSlider.value=n;pageSlider.max=totalPages;scrubMax.textContent=totalPages;
  $('prevBtn').disabled=n<=1;$('nextBtn').disabled=n>=totalPages;updateBookmarkButton();
}
function renderPage(n,resetScroll){
  if(!pdfDoc||viewMode==='continuous')return Promise.resolve();
  const doc=pdfDoc,token=renderGeneration.next();singleRenderTask?.cancel();singleTextTask?.cancel();
  singleRenderPromise=singleRenderPromise.catch(()=>{}).then(async()=>{
    if(!renderGeneration.isCurrent(token)||pdfDoc!==doc||viewMode!=='single')return;
    try{
      const page=await doc.getPage(n);
      if(!renderGeneration.isCurrent(token)||pdfDoc!==doc)return;
      const natural=page.getViewport({scale:1,rotation:curRotation}),insets=syncChromeInsets();
      const vw=Math.max(1,viewer.clientWidth-24),vh=Math.max(1,viewer.clientHeight-insets.top-insets.bottom-24);
      baseScale=vw/natural.width;
      const eff=ReaderCore.renderScale(natural.width,natural.height,vw,vh,fitMode,zoom);
      if(fitMode==='page')zoom=eff/baseScale;
      const ratio=ReaderCore.pixelRatio(natural.width*eff,natural.height*eff,DPR);
      const viewport=page.getViewport({scale:eff*ratio,rotation:curRotation});curViewport=viewport;
      canvas.width=Math.max(1,Math.floor(viewport.width));canvas.height=Math.max(1,Math.floor(viewport.height));
      canvas.style.width=Math.floor(natural.width*eff)+'px';canvas.style.height=Math.floor(natural.height*eff)+'px';wrap.style.transform='';
      const task=page.render({canvasContext:ctx,viewport});singleRenderTask=task;await task.promise;
      if(!renderGeneration.isCurrent(token)||pdfDoc!==doc)return;
      textLayer.replaceChildren();textLayer.style.width=canvas.style.width;textLayer.style.height=canvas.style.height;
      textLayer.style.setProperty('--scale-factor',eff);
      const text=await page.getTextContent();
      if(!renderGeneration.isCurrent(token)||pdfDoc!==doc)return;
      singleTextTask=pdfjsLib.renderTextLayer({textContentSource:text,container:textLayer,viewport:page.getViewport({scale:eff,rotation:curRotation}),textDivs:[]});
      await singleTextTask.promise;
      if(!renderGeneration.isCurrent(token)||pdfDoc!==doc)return;
      updatePageUI(n);highlightTextLayer();updateZoomLabel();
      if(resetScroll){viewer.scrollTop=0;viewer.scrollLeft=Math.max(0,(canvas.offsetWidth-viewer.clientWidth)/2);}
      saveCurrentMeta();
    }catch(err){if(err.name!=='RenderingCancelledException'&&renderGeneration.isCurrent(token))toast('페이지를 표시하지 못했습니다');}
    finally{if(renderGeneration.isCurrent(token)){singleRenderTask=null;singleTextTask=null;}}
  });
  return singleRenderPromise;
}

/* ── 연속 세로 스크롤 엔진 (Continuous Scroll Engine) ── */
async function initContinuousStage(targetPage=pageNum){
  if(!pdfDoc||viewMode!=='continuous')return;
  const doc=pdfDoc,gen=++continuousGeneration;isContinuousInitializing=true;
  renderGeneration.next();singleRenderTask?.cancel();singleTextTask?.cancel();
  continuousObserver?.disconnect();
  const old=Array.from(renderedPagesMap.values());old.forEach(state=>{state.task?.cancel();state.textTask?.cancel();});
  await Promise.all([singleRenderPromise.catch(()=>{}),...old.map(state=>state.promise?.catch(()=>{}))]);
  if(gen!==continuousGeneration||doc!==pdfDoc||viewMode!=='continuous')return;
  renderedPagesMap.clear();continuousStage.replaceChildren();syncChromeInsets();
  const first=await doc.getPage(1);
  if(gen!==continuousGeneration||doc!==pdfDoc)return;
  const vp=first.getViewport({scale:1,rotation:curRotation});
  const width=Math.max(1,Math.min(viewer.clientWidth-24,840)*zoom),height=width*vp.height/vp.width;
  const frag=document.createDocumentFragment();continuousPages=[];
  for(let i=1;i<=totalPages;i++){
    const el=document.createElement('div');el.className='scroll-page-wrap '+FILTERS[curFilterIndex];el.id='pageWrap_'+i;el.dataset.page=i;
    el.style.width=Math.floor(width)+'px';el.style.minHeight=Math.floor(height)+'px';
    el.innerHTML=`<div class="scroll-placeholder" id="placeholder_${i}"><span>${i} / ${totalPages} 쪽 불러오는 중…</span></div><canvas class="scroll-canvas" id="scrollCanvas_${i}" style="display:none"></canvas><div class="textLayer" data-page="${i}" id="scrollTextLayer_${i}" style="display:none"></div><div class="scroll-page-num">${i} 쪽</div>`;
    continuousPages.push(el);frag.appendChild(el);
  }
  continuousStage.appendChild(frag);
  continuousObserver=new IntersectionObserver(entries=>{
    if(gen!==continuousGeneration)return;
    for(const entry of entries){const p=Number(entry.target.dataset.page);if(entry.isIntersecting)renderContinuousPage(p);else unmountContinuousPage(p);}
  },{root:viewer,rootMargin:'500px 0px'});
  await renderContinuousPage(targetPage);
  if(gen!==continuousGeneration||doc!==pdfDoc)return;
  $('pageWrap_'+targetPage)?.scrollIntoView({behavior:'auto',block:'start'});
  continuousPages.forEach(el=>continuousObserver.observe(el));isContinuousInitializing=false;updatePageUI(targetPage);updateZoomLabel();
}
function renderContinuousPage(p){
  if(!pdfDoc||viewMode!=='continuous')return Promise.resolve();
  const existing=renderedPagesMap.get(p);
  if(existing&&!existing.failed){existing.wanted=true;return existing.promise;}
  if(existing?.failed)unmountContinuousPage(p);
  const gen=continuousGeneration,doc=pdfDoc,state={wanted:true,task:null,textTask:null,promise:null};
  renderedPagesMap.set(p,state);
  state.promise=(async()=>{
    try{
      const page=await doc.getPage(p);
      if(gen!==continuousGeneration||doc!==pdfDoc||viewMode!=='continuous')return;
      state.page=page;
      const el=$('pageWrap_'+p),cvs=$('scrollCanvas_'+p),layer=$('scrollTextLayer_'+p),ph=$('placeholder_'+p);
      if(!el||!cvs||!layer)return;
      const natural=page.getViewport({scale:1,rotation:curRotation}),width=el.clientWidth,eff=width/natural.width;
      const ratio=ReaderCore.pixelRatio(width,natural.height*eff,DPR),vp=page.getViewport({scale:eff*ratio,rotation:curRotation});
      cvs.width=Math.max(1,Math.floor(vp.width));cvs.height=Math.max(1,Math.floor(vp.height));cvs.style.width=width+'px';cvs.style.height=Math.floor(natural.height*eff)+'px';
      el.style.minHeight=cvs.style.height;cvs.style.display='block';if(ph)ph.style.display='none';
      state.task=page.render({canvasContext:cvs.getContext('2d',{alpha:false}),viewport:vp});await state.task.promise;
      if(gen!==continuousGeneration||doc!==pdfDoc)return;
      const content=await page.getTextContent();if(gen!==continuousGeneration||doc!==pdfDoc)return;
      layer.replaceChildren();layer.style.width=cvs.style.width;layer.style.height=cvs.style.height;layer.style.setProperty('--scale-factor',eff);layer.style.display='block';
      state.textTask=pdfjsLib.renderTextLayer({textContentSource:content,container:layer,viewport:page.getViewport({scale:eff,rotation:curRotation}),textDivs:[]});
      await state.textTask.promise;
      if(gen===continuousGeneration&&doc===pdfDoc)highlightContinuousPage(p,layer);
    }catch(err){if(gen===continuousGeneration&&err.name!=='RenderingCancelledException'){$('placeholder_'+p)?.replaceChildren(document.createTextNode(p+'쪽 표시 실패 — 페이지를 다시 선택해 주세요'));state.failed=true;}}
    finally{
      state.task=null;state.textTask=null;state.done=true;
      if(gen===continuousGeneration&&!state.wanted)unmountContinuousPage(p);
    }
  })();return state.promise;
}
function unmountContinuousPage(p){
  const state=renderedPagesMap.get(p);if(!state)return;state.wanted=false;
  if(!state.done)return;
  const cvs=$('scrollCanvas_'+p),layer=$('scrollTextLayer_'+p),ph=$('placeholder_'+p);
  if(cvs){cvs.width=1;cvs.height=1;cvs.style.display='none';}
  if(layer){layer.replaceChildren();layer.style.display='none';}
  if(ph)ph.style.display='flex';state.page?.cleanup();renderedPagesMap.delete(p);
}

function highlightContinuousPage(p,txtContainer){highlightLayer(txtContainer,p);}

// Track active page during continuous scroll
let scrollThrottleTimer = null;
viewer.addEventListener('scroll', () => {
  if(viewMode !== 'continuous' || !pdfDoc || isContinuousInitializing) return;
  if(scrollThrottleTimer) return;
  scrollThrottleTimer = setTimeout(() => {
    scrollThrottleTimer = null;
    updateContinuousActivePage();
  }, 90);
}, { passive: true });

function updateContinuousActivePage(){
  if(!continuousPages.length||!viewer.classList.contains('on'))return;
  const anchor=viewer.scrollTop+topbar.offsetHeight+Math.min(100,viewer.clientHeight/4);
  let lo=0,hi=continuousPages.length-1;
  while(lo<hi){const mid=Math.ceil((lo+hi)/2);if(continuousPages[mid].offsetTop<=anchor)lo=mid;else hi=mid-1;}
  const n=Number(continuousPages[lo].dataset.page);if(n!==pageNum){updatePageUI(n);saveCurrentMeta();}
}

function updateViewModeUI(){
  if(viewMode === 'continuous'){
    viewModeBtn.classList.add('active');
    viewModeBtn.title = '연속 스크롤 켜짐 (터치 시 단면 보기로 전환)';
  } else {
    viewModeBtn.classList.remove('active');
    viewModeBtn.title = '단면 보기 켜짐 (터치 시 연속 스크롤로 전환)';
  }
}

async function setViewMode(mode){
  const page=pageNum;viewMode=mode;
  try{localStorage.setItem('djs_view_mode',mode);}catch(_){}
  updateViewModeUI();await stopRendering();
  if(mode!==viewMode)return;
  if(mode==='continuous'){
    fitMode='width';zoom=1;fitBtnText.textContent='너비 맞춤';$('stage').style.display='none';continuousStage.style.display='flex';
    await initContinuousStage(page);toast('연속 세로 스크롤 모드');
  }else{continuousStage.style.display='none';$('stage').style.display='flex';await renderPage(page,true);toast('단면 넘김 모드');}
}
if(viewModeBtn)viewModeBtn.onclick=()=>setViewMode(viewMode==='continuous'?'single':'continuous');
async function gotoPage(n){
  if(!pdfDoc)return;n=Math.max(1,Math.min(totalPages,n|0));
  const previous=pageNum;updatePageUI(n);
  if(viewMode==='continuous'){
    await renderContinuousPage(n);$('pageWrap_'+n)?.scrollIntoView({behavior:'smooth',block:'start'});saveCurrentMeta();
  }else{animateFlip(n>previous?1:-1);await renderPage(n,true);}
}

window.gotoPage = gotoPage;

function animateFlip(dir){
  wrap.classList.add('flip');
  wrap.style.opacity = '0';
  wrap.style.transform = 'translateX(' + (dir * 24) + 'px)';
  setTimeout(()=>{ wrap.style.opacity='1'; wrap.style.transform=''; setTimeout(()=>wrap.classList.remove('flip'),220); },90);
}

/* ── 버튼 이벤트 ── */
$('prevBtn').onclick = () => gotoPage(pageNum - 1);
$('nextBtn').onclick = () => gotoPage(pageNum + 1);
$('homeBtn').onclick = goHome;
$('pageInput').addEventListener('change', e => gotoPage(parseInt(e.target.value) || 1));
$('pageInput').addEventListener('focus', e => e.target.select());

function goHome(){
  saveCurrentMeta();
  loadGeneration.next();candidateTask?.destroy().catch(()=>{});candidateTask=null;clearTimeout(scrollThrottleTimer);scrollThrottleTimer=null;stopRendering();
  viewer.classList.remove('on');
  topbar.classList.add('hide');
  bottombar.classList.add('hide');
  welcome.style.display = 'flex';
  toggleSearchBar(false);
  closeTocDrawer();
  releaseWakeLock();
  if (continuousObserver) { continuousObserver.disconnect(); continuousObserver = null; }
  renderedPagesMap.clear();
  continuousStage.innerHTML = '';
  if (window.AndroidBridge && window.AndroidBridge.setViewerActive) {
    window.AndroidBridge.setViewerActive(false);
  }
  renderRecent();
}

/* ── 테마 필터 (Dark / Sepia / Eye-Care Green) ── */
function applyFilterClass(cls, showToastNotify){
  FILTERS.forEach(f => {
    wrap.classList.remove(f);
    if(continuousStage){
      const items = continuousStage.querySelectorAll('.scroll-page-wrap');
      items.forEach(el => el.classList.remove(f));
    }
  });
  wrap.classList.add(cls);
  if(continuousStage){
    const items = continuousStage.querySelectorAll('.scroll-page-wrap');
    items.forEach(el => el.classList.add(cls));
  }
  $('filterBtn').classList.toggle('active', cls !== 'filter-default');
  if (showToastNotify) toast(FILTER_NAMES[cls]);
}

$('filterBtn').onclick = () => {
  curFilterIndex = (curFilterIndex + 1) % FILTERS.length;
  const newFilter = FILTERS[curFilterIndex];
  applyFilterClass(newFilter, true);
  saveCurrentMeta();
};

/* ── 회전 (Rotation) ── */
$('rotateBtn').onclick=()=>{
  curRotation=(curRotation+90)%360;toast(curRotation+'° 회전');
  if(viewMode==='continuous')initContinuousStage(pageNum).then(()=>saveCurrentMeta());else renderPage(pageNum,false);
};
function applyZoom(z){
  z=Math.max(MIN_ZOOM,Math.min(MAX_ZOOM,z));if(Math.abs(z-zoom)<.001)return;
  const ratio=z/zoom,cx=viewer.scrollLeft+viewer.clientWidth/2,cy=viewer.scrollTop+viewer.clientHeight/2;
  fitMode='custom';fitBtnText.textContent='너비 맞춤';zoom=z;
  if(viewMode==='continuous')initContinuousStage(pageNum);
  else renderPage(pageNum,false).then(()=>{viewer.scrollLeft=cx*ratio-viewer.clientWidth/2;viewer.scrollTop=cy*ratio-viewer.clientHeight/2;});
  updateZoomLabel();
}
function updateZoomLabel(){
  $('zoomPct').textContent=Math.round(zoom*100)+'%';$('fitBtn').classList.toggle('active',fitMode==='page'||fitMode==='width');
}
$('zoomIn').onclick=()=>applyZoom(zoom+.25);$('zoomOut').onclick=()=>applyZoom(zoom-.25);
$('fitBtn').onclick=async()=>{
  const mode=fitMode==='width'?'page':'width';
  if(mode==='page'&&viewMode==='continuous')await setViewMode('single');
  fitMode=mode;zoom=1;fitBtnText.textContent=mode==='page'?'전체 맞춤':'너비 맞춤';
  if(viewMode==='continuous')await initContinuousStage(pageNum);else await renderPage(pageNum,true);
  toast(mode==='page'?'전체 페이지 맞춤':'너비 맞춤');
};

/* ── 터치 제스처 (Pinch 줌, 스와이프, 더블탭) ── */
let touchStartX=0, touchStartY=0, touchStartT=0, lastTap=0, moved=false;
let pinch=false, pinchStartDist=0, pinchStartZoom=1, twoFinger=false;
function dist(t){ return Math.hypot(t[0].clientX - t[1].clientX, t[0].clientY - t[1].clientY); }

viewer.addEventListener('touchstart', e => {
  if(e.touches.length === 2){
    pinch = true; twoFinger = true;
    pinchStartDist = dist(e.touches);
    pinchStartZoom = zoom;
    wrap.classList.remove('flip');
  } else if(e.touches.length === 1){
    touchStartX = e.touches[0].clientX;
    touchStartY = e.touches[0].clientY;
    touchStartT = Date.now();
    moved = false;
  }
},{passive:true});

viewer.addEventListener('touchmove', e => {
  if(pinch && e.touches.length === 2){
    const live = pinchStartZoom * (dist(e.touches) / pinchStartDist);
    const clamped = Math.max(MIN_ZOOM, Math.min(MAX_ZOOM, live));
    wrap.style.transform = 'scale(' + (clamped / zoom) + ')';
  } else if(e.touches.length === 1){
    if(Math.abs(e.touches[0].clientX - touchStartX) > 8 || Math.abs(e.touches[0].clientY - touchStartY) > 8) moved = true;
  }
},{passive:true});

viewer.addEventListener('touchend', e => {
  if(pinch && e.touches.length < 2){
    pinch = false;
    const m = wrap.style.transform.match(/scale\(([\d.]+)\)/);
    if(m) applyZoom(zoom * parseFloat(m[1]));
    setTimeout(()=>{ twoFinger = false; }, 150);
    return;
  }
  if(twoFinger) return;
  const dx = e.changedTouches[0].clientX - touchStartX;
  const dy = e.changedTouches[0].clientY - touchStartY;
  const dt = Date.now() - touchStartT;
  if(zoom <= 1.02 && Math.abs(dx) > 60 && Math.abs(dx) > Math.abs(dy) * 1.6 && dt < 600){
    if(dx < 0) gotoPage(pageNum + 1); else gotoPage(pageNum - 1);
    return;
  }
  if(!moved && dt < 260){
    const now = Date.now();
    if(now - lastTap < 300){
      lastTap = 0;
      if(zoom > 1.02){applyZoom(1);}
      else applyZoom(2.2);
    } else {
      lastTap = now;
      setTimeout(()=>{ if(Date.now() - lastTap >= 300) toggleChrome(); }, 300);
    }
  }
},{passive:true});

function toggleChrome(){
  const hidden = topbar.classList.contains('hide');
  topbar.classList.toggle('hide', !hidden);
  bottombar.classList.toggle('hide', !hidden);
}

/* ── 드래그 앤 드롭 지원 ── */
window.addEventListener('dragover', e => {
  e.preventDefault();
  dropOverlay.classList.add('active');
});
window.addEventListener('dragleave', e => {
  if (e.relatedTarget === null) dropOverlay.classList.remove('active');
});
window.addEventListener('drop', e => {
  e.preventDefault();
  dropOverlay.classList.remove('active');
  const f = e.dataTransfer && e.dataTransfer.files && e.dataTransfer.files[0];
  if(f) openFile(f);
});

/* ── 키보드 단축키 ── */
document.addEventListener('keydown', e => {
  if(!viewer.classList.contains('on')) return;
  if(e.target === $('pageInput') || e.target === $('pwdInput') || e.target === searchInput) return;
  if((e.ctrlKey || e.metaKey) && (e.key === 'f' || e.key === 'F')){
    e.preventDefault();
    toggleSearchBar();
    return;
  }
  if(e.key === 'ArrowRight' || e.key === 'ArrowDown' || e.key === 'PageDown' || e.key === ' '){
    e.preventDefault(); gotoPage(pageNum + 1);
  } else if(e.key === 'ArrowLeft' || e.key === 'ArrowUp' || e.key === 'PageUp'){
    e.preventDefault(); gotoPage(pageNum - 1);
  } else if(e.key === '+' || e.key === '='){
    applyZoom(zoom + 0.25);
  } else if(e.key === '-'){
    applyZoom(zoom - 0.25);
  } else if(e.key === 'r' || e.key === 'R'){
    $('rotateBtn').click();
  } else if(e.key === 't' || e.key === 'T'){
    $('filterBtn').click();
  } else if(e.key === 'Escape'){
    if(!searchBar.classList.contains('hide')){
      toggleSearchBar(false);
    } else if(tocDrawer.classList.contains('open')){
      closeTocDrawer();
    } else {
      goHome();
    }
  }
});

viewer.addEventListener('wheel', e => {
  if(e.ctrlKey){ e.preventDefault(); applyZoom(zoom + (e.deltaY < 0 ? 0.15 : -0.15)); }
},{passive:false});

/* ── 최근 본 문서 렌더링 (IndexedDB 기반 즉시 열기, 검색 필터, 상단 고정) ── */
async function renderRecent(filterText = ''){
  const list = await idbGetAllMeta();
  const box = $('recentBox'), container = $('recentList');
  if(!list.length){ box.style.display = 'none'; return; }
  box.style.display = 'block';

  // Sort: pinned first (descending), then updatedAt (descending)
  list.sort((a, b) => {
    if (!!b.pinned !== !!a.pinned) return (b.pinned ? 1 : 0) - (a.pinned ? 1 : 0);
    return (b.updatedAt || 0) - (a.updatedAt || 0);
  });

  const query = (filterText || '').trim().toLowerCase();
  const filtered = query ? list.filter(item => (item.name||'').toLowerCase().includes(query)) : list;

  container.innerHTML = '';
  if (!filtered.length) {
    container.innerHTML = '<div style="padding:16px;text-align:center;color:var(--muted-soft);font-size:12.5px">일치하는 최근 문서가 없습니다</div>';
    return;
  }

  filtered.slice(0, 12).forEach(item => {
    const el = document.createElement('div');
    el.className = 'recent-item' + (item.pinned ? ' pinned' : '');
    el.innerHTML = `
      <div class="recent-icon">
        <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"/><path d="M14 2v6h6"/></svg>
      </div>
      <div class="recent-info">
        <div class="ri-name">${escapeHtml(item.name.replace(/\.pdf$/i,''))}</div>
        <div class="ri-meta">
          <span>${item.page} / ${item.totalPages}쪽</span>
          <span>•</span>
          <span>${formatDate(item.updatedAt)}</span>
        </div>
      </div>
      <button class="recent-pin ${item.pinned ? 'pinned' : ''}" aria-label="고정" title="${item.pinned ? '상단 고정 해제' : '상단 고정'}">
        <svg width="15" height="15" viewBox="0 0 24 24" fill="${item.pinned ? 'currentColor' : 'none'}" stroke="currentColor" stroke-width="2"><path d="M12 2l3.09 6.26L22 9.27l-5 4.87 1.18 6.88L12 17.77l-6.18 3.25L7 14.14 2 9.27l6.91-1.01L12 2z"/></svg>
      </button>
      <button class="recent-del" aria-label="삭제" title="기록 삭제">
        <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><line x1="18" y1="6" x2="6" y2="18"/><line x1="6" y1="6" x2="18" y2="18"/></svg>
      </button>
    `;
    
    /* 최근 문서 클릭 시 IndexedDB에서 바이너리를 즉시 읽어 열기 */
    el.onclick = async (e) => {
      if (e.target.closest('.recent-del') || e.target.closest('.recent-pin')) return;
      showLoader('문서 복원 중…');
      const buffer = await idbGetFile(item.key);
      if (!buffer) {
        hideLoader();
        toast('저장된 문서 데이터를 찾을 수 없습니다. 다시 열어주세요.');
        return;
      }
      loadDoc(buffer,item.name,{legacyKey:item.key.startsWith('pdf::')?item.key:undefined});
    };

    /* 상단 고정 (Pin) 버튼 */
    const pinBtn = el.querySelector('.recent-pin');
    pinBtn.onclick = async (e) => {
      e.stopPropagation();
      item.pinned = !item.pinned;
      if(!await idbSaveMeta(item)){warnStorage();return;}
      const searchBox = $('recentSearchInput');
      renderRecent(searchBox ? searchBox.value : '');
      toast(item.pinned ? '상단에 고정되었습니다' : '고정이 해제되었습니다');
    };
    
    /* 개별 삭제 버튼 */
    const delBtn = el.querySelector('.recent-del');
    delBtn.onclick = async (e) => {
      e.stopPropagation();
      if(!await idbDelete(item.key)){toast('문서 삭제에 실패했습니다');return;}
      const searchBox = $('recentSearchInput');
      renderRecent(searchBox ? searchBox.value : '');
      toast('기록에서 삭제되었습니다');
    };
    
    container.appendChild(el);
  });
}

const rSearch = $('recentSearchInput');
if (rSearch) {
  rSearch.addEventListener('input', e => {
    const val = e.target.value;
    const clr = $('clearRecentSearch');
    if (clr) clr.style.display = val ? 'inline-block' : 'none';
    renderRecent(val);
  });
}
const clrRSearch = $('clearRecentSearch');
if (clrRSearch) {
  clrRSearch.onclick = () => {
    if (rSearch) rSearch.value = '';
    clrRSearch.style.display = 'none';
    renderRecent('');
  };
}

$('clearRecentsBtn').onclick = async () => {
  if (!confirm('최근 본 문서 기록을 모두 지우시겠습니까?')) return;
  if(!await idbClearAll()){toast('기록을 지우지 못했습니다');return;}
  renderRecent();
  toast('모든 기록을 삭제했습니다');
};

function formatDate(ts){
  if(!ts) return '';
  const d = new Date(ts);
  const now = new Date();
  if (d.toDateString() === now.toDateString()) {
    return d.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
  }
  return `${d.getMonth()+1}/${d.getDate()}`;
}
function escapeHtml(s){ return s.replace(/[&<>"]/g, c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;'}[c])); }
renderRecent();

/* ── 로더 / 토스트 ── */
function showLoader(m){ loadMsg.textContent=m||'불러오는 중…'; loader.classList.add('on'); }
function hideLoader(){ loader.classList.remove('on'); }
let toastTimer=null;
function toast(m){
  toastEl.textContent = m;
  toastEl.classList.add('show');
  clearTimeout(toastTimer);
  toastTimer = setTimeout(()=> toastEl.classList.remove('show'), 2000);
}

let resizeT = null;
window.addEventListener('resize', ()=>{
  if(!pdfDoc || !viewer.classList.contains('on')) return;
  clearTimeout(resizeT);
  resizeT = setTimeout(()=>{syncChromeInsets();if(viewMode==='continuous')initContinuousStage(pageNum);else renderPage(pageNum,false);},180);
});

/* ── PWA 설치 프롬프트 및 서비스 워커 등록 ── */
let deferredPrompt = null;
window.addEventListener('beforeinstallprompt', e => {
  e.preventDefault();
  deferredPrompt = e;
  const btn = $('installBtn');
  if (btn) btn.style.display = 'inline-flex';
});

if ($('installBtn')) {
  $('installBtn').onclick = async () => {
    if (!deferredPrompt) {
      toast('브라우저 메뉴(⋮)에서 [앱 설치]를 선택해 주세요');
      return;
    }
    deferredPrompt.prompt();
    const { outcome } = await deferredPrompt.userChoice;
    if (outcome === 'accepted') {
      toast('앱 설치가 시작되었습니다');
      $('installBtn').style.display = 'none';
    }
    deferredPrompt = null;
  };
}

window.addEventListener('appinstalled', () => {
  toast('앱 설치가 완료되었습니다!');
  const btn = $('installBtn');
  if (btn) btn.style.display = 'none';
});

if('serviceWorker' in navigator && location.protocol.startsWith('http')){
  window.addEventListener('load', () => {
    navigator.serviceWorker.register('sw.js?v=5').then(reg => {
      return reg.update().catch(()=>console.info('오프라인 캐시를 유지합니다. 업데이트 확인은 다음 연결 때 재시도합니다.'));
    }).catch(err => console.warn('SW register error:', err));
  });
}

/* ── 네이티브 앱 연동 브리지 (Android Intent & File Handler) ── */
const incomingLoads=new Map();
window.openIncomingFromNative=function(fileName,id){
  if(!id||incomingLoads.has(id))return incomingLoads.get(id);
  const token=beginOpen();
  const work=(async()=>{
    try{
      const buffer=await new Promise((resolve,reject)=>{
        const xhr=new XMLHttpRequest();xhr.open('GET','./incoming.pdf?id='+encodeURIComponent(id));xhr.responseType='arraybuffer';xhr.timeout=30000;
        xhr.onprogress=event=>{if(event.loaded>ReaderCore.MAX_FILE_BYTES){xhr.abort();reject(new Error('256MB 이하 PDF를 열어 주세요'));}};
        xhr.onload=()=>{if((xhr.status===200||xhr.status===0)&&xhr.response?.byteLength)resolve(xhr.response);else reject(new Error('전달된 문서를 읽지 못했습니다'));};
        xhr.onerror=()=>reject(new Error('문서 전송 실패'));xhr.ontimeout=()=>reject(new Error('문서 전송 시간 초과'));xhr.send();
      });
      if(loadGeneration.isCurrent(token))await loadDoc(buffer,fileName||'외부문서.pdf',{token});
    }catch(err){if(loadGeneration.isCurrent(token)){hideLoader();toast('외부 PDF 열기 실패: '+err.message);}}
    finally{window.AndroidBridge?.onPdfLoaded?.(id);}
  })();
  incomingLoads.set(id,work);return work;
};

window.handleBackPressed = function() {
  if (pwdModal && pwdModal.classList.contains('on')) {
    cancelPassword();
    return 'Y';
  }
  if (tocDrawer && tocDrawer.classList.contains('open')) {
    closeTocDrawer();
    return 'Y';
  }
  if (searchBar && !searchBar.classList.contains('hide')) {
    toggleSearchBar(false);
    return 'Y';
  }
  if (viewer && viewer.classList.contains('on')) {
    $('homeBtn').click();
    return 'Y';
  }
  return 'N';
};

let nativeInitialized=false;
function initNativeBridge(){
  if(nativeInitialized||!window.AndroidBridge)return;nativeInitialized=true;
  if($('installBtn'))$('installBtn').style.display='none';
  $('versionText').textContent='DJS PDF v'+window.AndroidBridge.getAppVersion();
  const incoming=JSON.parse(window.AndroidBridge.getIncomingPdfInfo());
  if(incoming)window.openIncomingFromNative(incoming.name,incoming.id);
}
$('versionText').textContent='DJS PDF v'+APP_VERSION;
if($('appVersionBadge'))$('appVersionBadge').onclick=()=>{
  if(window.AndroidBridge?.checkUpdate)window.AndroidBridge.checkUpdate();
  else toast('현재 앱 버전: v'+APP_VERSION);
};
window.addEventListener('DOMContentLoaded',initNativeBridge,{once:true});
window.addEventListener('visibilitychange',()=>{if(document.visibilityState==='visible'&&viewer.classList.contains('on'))requestWakeLock();});

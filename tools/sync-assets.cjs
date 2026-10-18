const fs=require('node:fs'),path=require('node:path');
const crypto=require('node:crypto');
const root=path.resolve(__dirname,'..'),target=path.join(root,'android/app/src/main/assets');
const names=['index.html','app.js','reader-core.js','reader-store.js','pdf.min.js','pdf.worker.min.js','sw.js','manifest.json','version.json','favicon.png','apple-touch-icon.png','icon-192.png','icon-512.png','icon-maskable-192.png','icon-maskable-512.png'];
const digest=crypto.createHash('sha256');
for(const name of names.filter(n=>n!=='sw.js'))digest.update(name).update(fs.readFileSync(path.join(root,name)));
const cacheName='djs-pdf-app:1.2.1-'+digest.digest('hex').slice(0,16);
const swPath=path.join(root,'sw.js'),sw=fs.readFileSync(swPath,'utf8');
const updated=sw.replace(/const CACHE_NAME=[^;]+;/,`const CACHE_NAME='${cacheName}';`);
if(process.argv.includes('--check')){
  if(updated!==sw){console.error('Service worker asset generation is stale; run npm run sync-assets.');process.exit(1);}
}else if(updated!==sw)fs.writeFileSync(swPath,updated);
let mismatches=[];
for(const name of names){
  const source=fs.readFileSync(path.join(root,name)),dest=path.join(target,name);
  if(process.argv.includes('--check')){if(!fs.existsSync(dest)||!source.equals(fs.readFileSync(dest)))mismatches.push(name);}
  else fs.writeFileSync(dest,source);
}
if(mismatches.length){console.error('Android asset mirror is stale:',mismatches.join(', '));process.exitCode=1;}
else console.log(process.argv.includes('--check')?'Web/Android assets match.':'Android asset mirror synchronized.');

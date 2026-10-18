const path=require('node:path'),fs=require('node:fs'),{spawnSync}=require('node:child_process');
const root=path.resolve(__dirname,'..'),output=path.join(root,'_QA/java-tests');fs.mkdirSync(output,{recursive:true});
const binary=name=>process.env.JAVA_HOME?path.join(process.env.JAVA_HOME,'bin',name+(process.platform==='win32'?'.exe':'')):name;
for(const [name,args] of [
  ['javac',['-encoding','UTF-8','-d',output,path.join(root,'android/app/src/main/java/com/ldj/djspdf/FileSafety.java'),path.join(root,'tests/FileSafetyTest.java')]],
  ['java',['-cp',output,'com.ldj.djspdf.FileSafetyTest',output]]
]){const result=spawnSync(binary(name),args,{stdio:'inherit'});if(result.status!==0)process.exit(result.status||1);}

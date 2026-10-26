'use strict';
const {spawn}=require('node:child_process');
const fs=require('node:fs/promises'),path=require('node:path');
const supported=p=>/\.(jpe?g|png|webp)$/i.test(p);
async function collect(inputs,outputDir){
  const stack=[...inputs],files=new Set(),seen=new Set();let examined=0;
  const excluded=path.resolve(outputDir);
  while(stack.length){const input=path.resolve(stack.pop());if(++examined>100000)throw Error('Choose a smaller folder: more than 100,000 entries.');const stat=await fs.lstat(input);if(stat.isSymbolicLink())continue;const actual=await fs.realpath(input);if(actual===excluded||actual.startsWith(excluded+path.sep)||seen.has(actual))continue;seen.add(actual);
    if(stat.isDirectory()){for(const entry of await fs.readdir(actual,{withFileTypes:true})){if(['.git','node_modules','.pichalite'].includes(entry.name)||entry.name.startsWith('.pichalite-'))continue;stack.push(path.join(actual,entry.name));}}
    else if(stat.isFile()&&supported(actual)){files.add(actual);if(files.size>5000)throw Error('Choose at most 5,000 images per batch.');}
  }return [...files].sort();
}
function run(executable,request,{spawnImpl=spawn,timeoutMs=120000}={}){
  return new Promise((resolve,reject)=>{
    const child=spawnImpl(executable,[],{shell:false,windowsHide:true,stdio:['pipe','pipe','pipe']});let output='',errors='',settled=false;
    const finish=(error,value)=>{if(settled)return;settled=true;clearTimeout(timer);error?reject(error):resolve(value);};
    const timer=setTimeout(()=>{child.kill();finish(Error('Compression timed out. Your original is unchanged.'));},timeoutMs);
    child.on('error',error=>finish(error));child.stdin.on('error',error=>finish(error));
    child.stdout.on('data',data=>{output+=data;if(Buffer.byteLength(output)>8*1024*1024){child.kill();finish(Error('PichaLite returned too much output.'));}});
    child.stderr.on('data',data=>{if(errors.length<8192)errors+=data;});
    child.on('close',code=>{if(settled)return;try{const lines=output.trim().split(/\r?\n/).filter(Boolean).map(line=>JSON.parse(line));if(!lines.length||!lines.every(r=>['compressed','retained','failed'].includes(r.status)))throw Error('Invalid result');if(code!==0&&code!==2)throw Error(errors||`PichaLite exited with code ${code}`);finish(null,lines);}catch(error){finish(Error(errors.trim()||`PichaLite could not finish: ${error.message}`));}});
    child.stdin.end(JSON.stringify(request));
  });
}
module.exports={collect,run};

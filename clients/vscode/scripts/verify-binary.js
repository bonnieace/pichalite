const fs=require('node:fs'),path=require('node:path');
const file=path.join(__dirname,'..','bin','pichalite-cli.exe');
if(!fs.existsSync(file))throw Error('Missing bundled Windows engine. Build pichalite-cli.exe and copy it to clients/vscode/bin before packaging.');
const fd=fs.openSync(file,'r');const bytes=Buffer.alloc(2);fs.readSync(fd,bytes,0,2,0);fs.closeSync(fd);
if(bytes.toString()!=='MZ')throw Error('Bundled engine is not a Windows executable.');
console.log('Bundled Windows engine found.');

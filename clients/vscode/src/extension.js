'use strict';
const vscode=require('vscode'),path=require('node:path'),fs=require('node:fs/promises');
const {collect,run}=require('./runner');
const size=n=>n<1048576?(n/1024).toFixed(1)+' KB':(n/1048576).toFixed(2)+' MB';
function activate(context){
 const log=vscode.window.createOutputChannel('PichaLite');context.subscriptions.push(log);let busy=false;
 context.subscriptions.push(vscode.commands.registerCommand('pichalite.compress',async(uri,selected)=>{
  if(!vscode.workspace.isTrusted)return vscode.window.showErrorMessage('Trust this workspace before running PichaLite.');
  if(busy)return vscode.window.showInformationMessage('PichaLite is already compressing a batch.');busy=true;
  try{
    const config=vscode.workspace.getConfiguration('pichalite');
    let executable=config.get('executablePath','');
    if(!executable){if(process.platform!=='win32'||process.arch!=='x64')throw Error('This package bundles the Windows x64 engine. Set PichaLite: Executable Path to a matching pichalite-cli build for this computer.');executable=context.asAbsolutePath('bin/pichalite-cli.exe');}
    if(!path.isAbsolute(executable))throw Error('PichaLite executable path must be absolute.');
    await fs.access(executable);
    let choices=selected?.length?selected:uri?[uri]:[];
    if(!choices.length){const kind=await vscode.window.showQuickPick(['Choose images','Choose a folder'],{title:'Compress with PichaLite'});if(!kind)return;choices=await vscode.window.showOpenDialog({canSelectFiles:kind==='Choose images',canSelectFolders:kind==='Choose a folder',canSelectMany:kind==='Choose images',filters:kind==='Choose images'?{'Photos':['jpg','jpeg','png','webp']}:undefined,openLabel:'Choose for PichaLite'})||[];}
    if(!choices.length)return;if(choices.some(u=>u.scheme!=='file'))throw Error('Choose files stored on this computer.');
    const output=await vscode.window.showOpenDialog({canSelectFiles:false,canSelectFolders:true,canSelectMany:false,openLabel:'Save PichaLite copies here',title:'Choose a destination folder (originals stay untouched)'});if(!output?.length)return;
    const outputDir=path.join(output[0].fsPath,'PichaLite');await fs.mkdir(outputDir,{recursive:true});
    const files=await collect(choices.map(u=>u.fsPath),outputDir);if(!files.length)return vscode.window.showInformationMessage('No JPEG, PNG or WebP images found.');
    const quality=config.get('quality',82),maxEdge=config.get('maxEdge',4096);
    const confirm=await vscode.window.showInformationMessage(`Compress ${files.length} image(s) to JPEG at quality ${quality}, max edge ${maxEdge}px? Copies go to ${outputDir}. Transparent areas become white. Originals stay untouched.`,{modal:true},'Compress copies');if(confirm!=='Compress copies')return;
    let completed=0,failed=0,saved=0,cancelled=false;log.clear();log.appendLine(`Destination: ${outputDir}`);
    await vscode.window.withProgress({location:vscode.ProgressLocation.Notification,title:'PichaLite',cancellable:true},async(progress,token)=>{
      for(const [i,input] of files.entries()){
        if(token.isCancellationRequested){cancelled=true;break;}
        progress.report({message:`${i+1}/${files.length}: ${path.basename(input)}`});
        try{const results=await run(executable,{inputs:[input],output_dir:outputDir,quality,max_edge:maxEdge});for(const r of results){if(r.status==='failed'){failed++;log.appendLine(`${input}: ${r.error}`);}else{completed++;saved+=r.saved_bytes;log.appendLine(`${r.status}: ${r.input} → ${r.output} (${size(r.input_bytes)} → ${size(r.output_bytes)})`);}}}catch(error){failed++;log.appendLine(`${input}: ${error.message}`);}
        progress.report({increment:100/files.length});
      }
    });
    const action=await vscode.window.showInformationMessage(`PichaLite: ${completed} saved, ${size(saved)} smaller${failed?`, ${failed} failed`:''}${cancelled?' · stopped after current photo':''}.`,'Open output folder','View results');
    if(action==='Open output folder')await vscode.env.openExternal(vscode.Uri.file(outputDir));else if(action==='View results')log.show();
  }catch(error){log.appendLine(error.stack||String(error));vscode.window.showErrorMessage(`PichaLite: ${error.message}`);}finally{busy=false;}
 }));
}
module.exports={activate};

const {test}=require('node:test'),assert=require('node:assert/strict');
const fs=require('node:fs/promises'),os=require('node:os'),path=require('node:path');
const {run}=require('../src/runner');
test('extension runner compresses a real image through the bundled CLI protocol',{skip:!process.env.PICHA_TEST_CLI},async()=>{
 const root=await fs.mkdtemp(path.join(os.tmpdir(),'pichalite-cli-'));try{
  const input=path.join(root,'photo with spaces.png');await fs.copyFile(path.join(__dirname,'fixtures','photo.png'),input);const original=await fs.readFile(input);
  const request={inputs:[input],output_dir:path.join(root,'out'),quality:82,max_edge:256};
  const [result]=await run(process.env.PICHA_TEST_CLI,request);assert.equal(result.status,'retained');assert.equal(result.saved_bytes,0);assert.deepEqual(await fs.readFile(input),original);assert.deepEqual(await fs.readFile(result.output),original);
  const [next]=await run(process.env.PICHA_TEST_CLI,request);assert.notEqual(next.output,result.output);
 }finally{await fs.rm(root,{recursive:true,force:true});}
});

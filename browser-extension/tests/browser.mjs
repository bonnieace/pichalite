// Real Chromium + simulated Google Photos. No real Google account or photos are accessed.
// This tests the shipped DOM adapter, image encoder, IndexedDB, runner and UI together.
import {createRequire} from 'node:module';
import {readFile, mkdir} from 'node:fs/promises';
import {createServer} from 'node:http';
import {fileURLToPath} from 'node:url';
import assert from 'node:assert/strict';
const require = createRequire(import.meta.url);
const engines = require('playwright');
const engineName = process.env.PICHALITE_BROWSER || 'chromium';
if (!['chromium', 'firefox', 'webkit'].includes(engineName)) throw new Error('Unknown browser engine');
const root = fileURLToPath(new URL('../', import.meta.url));
const fixtureAccount = 'pichalite-fixture@example.test';
const library = new Map();
const local = {};
let uploads = 0, trashes = 0, switchAccount = false, corruptReplacement = false;
const server = createServer(async (req, res) => {
  const name = new URL(req.url, 'http://localhost').pathname.slice(1) || 'app.html';
  if (!/^[a-z]+\.(html|css|js)$/.test(name)) { res.writeHead(404).end(); return; }
  try { res.setHeader('content-type', name.endsWith('.js') ? 'text/javascript' : name.endsWith('.css') ? 'text/css' : 'text/html'); res.end(await readFile(root + name)); }
  catch { res.writeHead(404).end(); }
});
await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
const origin = `http://127.0.0.1:${server.address().port}`;
const browser = await engines[engineName].launch({executablePath: engineName === 'chromium' ? process.env.PICHALITE_CHROMIUM || undefined : undefined, headless: true, args: engineName === 'chromium' ? ['--no-sandbox','--disable-dev-shm-usage'] : []});
const context = await browser.newContext({viewport: {width: 420, height: 920}, serviceWorkers: 'block'});
const errors = [];
let app;
const pages = new Map();
let nextTab = 1;
function fixtureHTML(url) {
  const id = new URL(url).pathname.match(/\/photo\/([^/]+)/)?.[1];
  const account = switchAccount ? 'changed@example.test' : fixtureAccount;
  const accountControl = `<button aria-label="Google Account: Fixture (${account})">Account</button>`;
  if (id) {
    return `<!doctype html><html><body>${accountControl}<h1>${id}</h1><img width="200" src="https://lh3.googleusercontent.com/${id}"><button aria-label="More options" id="more">More</button><div id="menu" hidden><a role="menuitem" href="https://lh3.googleusercontent.com/${id}">Download</a></div><button aria-label="Delete" id="delete">Delete</button><script>
    document.getElementById('more').onclick=()=>document.getElementById('menu').hidden=false;
    document.getElementById('delete').onclick=()=>{const d=document.createElement('div');d.setAttribute('role','dialog');d.innerHTML='<p>Move to trash?</p><button id="confirm">Move to trash</button>';document.body.append(d);d.querySelector('button').onclick=async()=>{await fetch('/fixture-trash/${id}',{method:'POST'});d.remove();const s=document.createElement('div');s.setAttribute('role','status');s.textContent='Moved to trash';document.body.append(s)}};
    </script></body></html>`;
  }
  const links = [...library].filter(([,v]) => !v.trashed).reverse().map(([id]) => `<a href="/photo/${id}" aria-label="Photo ${id}"><img width="80" height="80" alt="${id}" src="https://lh3.googleusercontent.com/${id}"></a>`).join('');
  return `<!doctype html><html><body>${accountControl}<h1>Photos</h1>${links}<button aria-label="Create and add photos" id="create">Create</button><script>
    document.getElementById('create').onclick=()=>{const menu=document.createElement('ul');menu.setAttribute('role','menu');menu.innerHTML='<li role="menuitem" aria-label="Import photos from your computer">Import photos</li>';document.body.append(menu);menu.querySelector('li').onclick=()=>{const input=document.createElement('input');input.type='file';input.accept='image/*';input.onchange=async(event)=>{await fetch('/fixture-upload',{method:'POST',body:JSON.stringify([...new Uint8Array(await event.target.files[0].arrayBuffer())])});const s=document.createElement('div');s.setAttribute('role','status');s.textContent='Upload complete';document.body.append(s)};input.click();menu.remove()}};
    </script></body></html>`;
}
await context.route('https://photos.google.com/**', async route => {
  const path = new URL(route.request().url()).pathname;
  // Textual fixture transport lets WebKit's interception protocol expose the file bytes.
  if (path === '/fixture-upload') {
    uploads++; library.set(`replacement${uploads}`, {bytes: Buffer.from(JSON.parse(route.request().postData())), trashed: false});
    return route.fulfill({status: 200, body: 'ok'});
  }
  if (path.startsWith('/fixture-trash/')) {
    trashes++; library.get(path.split('/').at(-1)).trashed = true;
    return route.fulfill({status: 200, body: 'ok'});
  }
  await route.fulfill({contentType: 'text/html', body: fixtureHTML(route.request().url())});
});
await context.route('https://lh3.googleusercontent.com/**', async route => {
  const id = new URL(route.request().url()).pathname.slice(1), item = library.get(id);
  if (!item || item.trashed) return route.fulfill({status:404,body:'missing'});
  const body = corruptReplacement && id.startsWith('replacement') ? Buffer.from('changed image') : item.bytes;
  await route.fulfill({contentType:'image/jpeg',body,headers:{'Access-Control-Allow-Origin':origin,'Access-Control-Allow-Credentials':'true'}});
});
const content = await readFile(root + 'content.js', 'utf8');
async function openPhotos(url) {
  const page = await context.newPage(), id = nextTab++;
  pages.set(id, page); await page.goto(url); await page.addScriptTag({content}); return id;
}
async function send(tabId, message) {
  return pages.get(tabId).evaluate(message => new Promise(resolve => window.__photoListener(message, {id:'fixture-extension'}, resolve)), message);
}
await context.exposeBinding('fixtureBridge', async ({page}, op, ...args) => {
  if (op === 'get') { const keys=args[0]; return keys===null ? {...local} : Object.fromEntries((Array.isArray(keys)?keys:[keys]).filter(k=>k in local).map(k=>[k,local[k]])); }
  if (op === 'set') { Object.assign(local,args[0]); return; }
  if (op === 'remove') { for(const k of Array.isArray(args[0])?args[0]:[args[0]]) delete local[k];return; }
  if (op === 'query') return [...pages].map(([id,p])=>({id,url:p.url(),active:id===1,status:'complete'}));
  if (op === 'create') { const url=args[0].url; if(!url.startsWith('https://photos.google.com')) throw new Error('Unexpected fixture tab'); return {id:await openPhotos(url)}; }
  if (op === 'update') { const [id,{url}]=args;await pages.get(id).goto(url);await pages.get(id).addScriptTag({content});return {id,url,status:'complete'}; }
  if (op === 'tab') return {id:args[0],url:pages.get(args[0]).url(),status:'complete'};
  if (op === 'send') return send(...args);
  if (op === 'execute') return pages.get(args[0]).evaluate(`(${args[1]})()`);
  if (op === 'download') { const message=args[0];const result=await send(message.tabId,{type:'pichalite-command',command:'download',expectedURL:message.url,account:message.account});return result; }
  throw new Error(`Unknown fixture operation ${op}`);
});
await context.addInitScript(({origin}) => {
  const bridge=(op,...args)=>window.fixtureBridge(op,...args);
  window.chrome={runtime:{id:'fixture-extension',getURL:path=>origin+'/'+path,onMessage:{addListener:listener=>{window.__photoListener=listener}},sendMessage:message=>bridge('download',message)},storage:{local:{get:keys=>bridge('get',keys),set:values=>bridge('set',values),remove:keys=>bridge('remove',keys)},onChanged:{addListener:()=>{}}},tabs:{query:()=>bridge('query'),create:value=>bridge('create',value),update:(...args)=>bridge('update',...args),get:id=>bridge('tab',id),sendMessage:(...args)=>bridge('send',...args)},scripting:{executeScript:({target,func})=>bridge('execute',target.tabId,func.toString())}};
  if (!/Chrome\//.test(navigator.userAgent)) { window.browser = window.chrome; delete window.chrome; }
}, {origin});
try {
  // Create a photographic, high-quality original using the browser itself.
  const generator=await context.newPage();
  const source=await generator.evaluate(async()=>{
    const c=new OffscreenCanvas(800,600),ctx=c.getContext('2d');
    const data=ctx.createImageData(800,600);let seed=37;
    for(let i=0;i<data.data.length;i+=4){seed=(seed*1664525+1013904223)>>>0;data.data[i]=seed&255;data.data[i+1]=(seed>>>8)&255;data.data[i+2]=(seed>>>16)&255;data.data[i+3]=255}
    ctx.putImageData(data,0,0);return [...new Uint8Array(await (await c.convertToBlob({type:'image/jpeg',quality:1})).arrayBuffer())];
  });
  await generator.close();
  library.set('original1',{bytes:Buffer.from(source),trashed:false});
  const sourceId=await openPhotos('https://photos.google.com/');
  // Exercise the real shadow-root toolbar and click interception.
  const photoPage=pages.get(sourceId);
  await photoPage.evaluate(()=>{document.querySelector('#pichalite-selection-toolbar').remove()});
  // Reinstall once with an open shadow root in the fixture so Playwright can operate the controls.
  await photoPage.evaluate(()=>{delete globalThis.__pichaliteInstalled});
  await photoPage.addScriptTag({content:content.replace("attachShadow({mode: 'closed'})","attachShadow({mode: 'open'})")});
  await photoPage.getByRole('button',{name:'Select photos',exact:true}).click();
  await photoPage.getByRole('link',{name:'Photo original1'}).click();
  await photoPage.waitForFunction(()=>document.querySelector('#pichalite-selection-toolbar').shadowRoot.querySelector('#count').textContent==='1 selected');
  assert.equal(Object.keys(local).filter(k=>k.startsWith('selection:')).length,1);
  app=await context.newPage();app.on('pageerror',error=>errors.push(error.message));
  await app.goto(origin+'/app.html');
  await app.waitForFunction(()=>document.getElementById('account').textContent.includes('example.test'));
  await app.getByRole('button',{name:'Add to batch'}).click();
  await app.waitForSelector('.item');
  assert(await app.getByRole('button',{name:'Compress & upload'}).isDisabled());
  await app.locator('#metadata').check();
  await app.getByRole('button',{name:'Compress & upload'}).click();
  await app.waitForFunction(()=>document.querySelector('.state')?.textContent==='verified'||document.querySelector('.item .error'),{},{timeout:60000});
  assert.equal(await app.locator('.state').textContent(),'verified',await app.locator('#items').textContent());
  assert.equal(uploads,1);assert.equal(trashes,0);
  let records=await app.evaluate(async()=>{const db=await import('./db.js');return db.allItems()});
  assert.equal(records[0].state,'verified');assert(records[0].outputBytes<records[0].originalBytes);
  assert.equal(records[0].width,800);assert.equal(records[0].height,600);
  assert.equal(await app.evaluate(()=>document.documentElement.scrollWidth>innerWidth),false);
  await mkdir(root+'test-results',{recursive:true});
  await app.screenshot({path:root+'test-results/verified-batch.png',fullPage:true});
  // Replacement mutation must stop cleanup without touching the original.
  corruptReplacement=true;
  await app.locator('#cleanup').click();await app.locator('#confirm-cleanup').click();
  await app.waitForFunction(()=>document.querySelector('.state')?.textContent==='review');
  assert.equal(trashes,0);assert(!library.get('original1').trashed);
  corruptReplacement=false;
  await app.getByRole('button',{name:'Reconcile',exact:true}).click();
  // An existing verified copy has a beforeLinks snapshot excluding this upload, so it can be reconciled.
  await app.waitForFunction(()=>document.querySelector('.state')?.textContent==='verified',{},{timeout:60000});
  // A changed Google account must also block cleanup.
  switchAccount=true;
  await app.locator('#cleanup').click();await app.locator('#confirm-cleanup').click();
  await app.waitForFunction(()=>document.querySelector('.state')?.textContent==='review',{},{timeout:30000});
  assert.equal(trashes,0);
  switchAccount=false;
  await app.getByRole('button',{name:'Reconcile',exact:true}).click();
  await app.waitForFunction(()=>document.querySelector('.state')?.textContent==='verified',{},{timeout:60000});
  await app.locator('#cleanup').click();await app.locator('#confirm-cleanup').click();
  await app.waitForFunction(()=>document.querySelector('.state')?.textContent==='trashed',{},{timeout:30000});
  assert.equal(trashes,1);assert(library.get('original1').trashed);assert(!library.get('replacement1').trashed);
  assert.equal(uploads,1);
  // The completed batch survives a reload and is not uploaded or trashed a second time.
  app.on('dialog',dialog=>dialog.dismiss());
  await app.reload();await app.waitForSelector('.state');
  assert.equal(await app.locator('.state').textContent(),'trashed');assert.equal(uploads,1);assert.equal(trashes,1);
  assert.deepEqual(errors,[]);
  console.log(engineName + ' PASS: selection → real browser compression → upload → exact-byte verification → mutation/account guards → approved trash → reload persistence.');
  console.log('Fixture only: live Google Photos and actual extension API integration still require validation.');
} catch(error) {
  console.error('Engine:',engineName,'Page errors:',errors);
  if(app)console.error('Application state:',await app.locator('body').textContent().catch(()=>'(unavailable)'));
  throw error;
} finally { await context.close();await browser.close();await new Promise(resolve=>server.close(resolve)); }

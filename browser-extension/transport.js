import {ext} from './api.js';
import {assertAccount, mediaURL, MAX_BYTES} from './core.js';
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));
export async function command(tabId, command, args = {}) {
  const result = await ext.tabs.sendMessage(tabId, {type: 'pichalite-command', command, ...args});
  if (!result?.ok) throw new Error(result?.error || 'Google Photos did not respond. Reload its tab and try again.');
  return result.value;
}
export async function navigate(tabId, url, account) {
  if (new URL(url).origin !== 'https://photos.google.com') throw new Error('Unexpected navigation');
  await ext.tabs.update(tabId, {url});
  const deadline = Date.now() + 20000;
  let lastError;
  while (Date.now() < deadline) {
    await sleep(250);
    try {
      const tab = await ext.tabs.get(tabId);
      if (tab.status !== 'complete' || tab.url !== url) continue;
      const context = await command(tabId, 'describe');
      assertAccount(account, context.account);
      if (context.url === url) return context;
    } catch (error) { lastError = error; }
  }
  throw lastError || new Error('Google Photos did not load the expected page. Sign in to the original account.');
}
export async function download(tabId, url, account) {
  const result = await ext.runtime.sendMessage({type: 'download-original', tabId, url, account});
  if (!result?.ok) throw new Error(result?.error || 'Could not obtain original image');
  const response = await fetch(mediaURL(result.value.url), {credentials: 'include', signal: AbortSignal.timeout(60000), cache: 'no-store'});
  if (!response.ok) throw new Error(`Download failed (${response.status})`);
  mediaURL(response.url);
  if (Number(response.headers.get('content-length')) > MAX_BYTES) throw new Error('Original exceeds 40 MB');
  const reader = response.body.getReader();
  const chunks = [];
  let size = 0;
  try {
    while (true) {
      const {done, value} = await reader.read();
      if (done) break;
      size += value.length;
      if (size > MAX_BYTES) throw new Error('Original exceeds 40 MB');
      chunks.push(value);
    }
  } catch (error) { await reader.cancel(); throw error; }
  if (!size) throw new Error('Empty image download');
  const after = await command(tabId, 'describe');
  assertAccount(account, after.account);
  if (after.url !== url) throw new Error('The photo changed during download. Operation stopped.');
  return new Blob(chunks, {type: response.headers.get('content-type') || 'application/octet-stream'});
}
// Executed as packaged code in the page's main world. Google Photos may create
// a detached input, so a normal DOM query alone cannot reliably find it.
// Capture exactly one file-picker request in our dedicated work tab, attach its
// input for the content script, and restore native methods immediately.
export function armUploadInput() {
  const proto = HTMLInputElement.prototype;
  const originalClick = proto.click, originalPicker = proto.showPicker;
  let timer;
  function restore() {
    if (proto.click === click) proto.click = originalClick;
    if (proto.showPicker === picker) proto.showPicker = originalPicker;
    clearTimeout(timer);
  }
  function capture(input) {
    if (input.type !== 'file' || (input.accept && !/image|\.jp/i.test(input.accept))) return false;
    restore();
    if (!input.isConnected) { input.hidden = true; document.documentElement.append(input); }
    return true;
  }
  function click(...args) { if (!capture(this)) return originalClick.apply(this, args); }
  function picker(...args) { if (!capture(this)) return originalPicker.apply(this, args); }
  proto.click = click;
  if (originalPicker) proto.showPicker = picker;
  timer = setTimeout(restore, 15000);
}
export async function upload(tabId, item, blob, name) {
  await command(tabId, 'upload-start', {account: item.account, id: item.id, name});
  const bytes = new Uint8Array(await blob.arrayBuffer());
  for (let offset = 0; offset < bytes.length; offset += 192 * 1024) {
    const part = bytes.subarray(offset, offset + 192 * 1024);
    let binary = '';
    for (let n = 0; n < part.length; n += 8192) binary += String.fromCharCode(...part.subarray(n, n + 8192));
    await command(tabId, 'upload-chunk', {account: item.account, id: item.id, chunk: btoa(binary)});
  }
  await ext.scripting.executeScript({target: {tabId}, world: 'MAIN', func: armUploadInput});
  await command(tabId, 'upload-commit', {account: item.account, id: item.id});
}

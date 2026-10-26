import {allItems, forgetItem, getBlob, putItem} from './db.js';
import {eligible, outputName, photoIdentity, totals} from './core.js';
import {command} from './transport.js';
import {Runner} from './runner.js';

const $ = id => document.getElementById(id);
let account = '', sourceTab, items = [], cleanupIds = [], linkedItem;
const runner = new Runner(render);
const bytes = value => value < 1024 ? `${value} B` : value < 1024 ** 2 ? `${(value / 1024).toFixed(1)} KB` : value < 1024 ** 3 ? `${(value / 1024 ** 2).toFixed(1)} MB` : `${(value / 1024 ** 3).toFixed(2)} GB`;
function notice(message) { $('notice').hidden = !message; $('notice').textContent = message; }
async function edit(task) {
  return navigator.locks.request('pichalite-photo-runner', {ifAvailable: true}, async lock => {
    if (!lock) throw new Error('A batch is running in another panel or tab. Pause it before editing local records.');
    return task();
  });
}
async function action(task) {
  try { notice(''); await task(); }
  catch (error) { notice(error.message); }
  finally { await render(); }
}
async function connect() {
  const tabs = await chrome.tabs.query({url: 'https://photos.google.com/*'});
  const candidates = tabs.filter(tab => tab.id !== runner.tabId).sort((a, b) => Number(b.active) - Number(a.active));
  if (!candidates.length) {
    await chrome.tabs.create({url: 'https://photos.google.com/'});
    throw new Error('Sign in to Google Photos, then choose Connect tab.');
  }
  sourceTab = candidates[0].id;
  let context;
  try { context = await command(sourceTab, 'describe'); }
  catch {
    await chrome.scripting.executeScript({target: {tabId: sourceTab}, files: ['content.js']});
    context = await command(sourceTab, 'describe');
  }
  account = context.account;
  $('account').textContent = account;
  await render();
}
async function importSelection() {
  if (!account) await connect();
  const stored = await chrome.storage.local.get(null);
  const chosen = Object.entries(stored).filter(([key, value]) => key.startsWith('selection:') && value.account === account);
  if (!chosen.length) throw new Error('Select photos with the PichaLite toolbar in Google Photos first.');
  await navigator.locks.request('pichalite-photo-runner', {ifAvailable: true}, async lock => {
    if (!lock) throw new Error('Pause the active batch before adding more photos');
    const current = await allItems();
    for (const [key, selected] of chosen) {
      const identity = photoIdentity(selected.originalURL);
      if (!current.some(item => item.account === account && photoIdentity(item.originalURL) === identity)) {
        const item = {...selected, id: crypto.randomUUID(), state: 'selected', createdAt: Date.now()};
        await putItem(item); current.push(item);
      }
      await chrome.storage.local.remove(key);
    }
  });
}
function smallButton(label, handler) {
  const button = document.createElement('button'); button.className = 'text-button'; button.textContent = label;
  button.disabled = runner.busy;
  button.onclick = () => action(handler);
  return button;
}
function link(label, url) {
  const anchor = document.createElement('a'); anchor.className = 'text-button'; anchor.textContent = label; anchor.href = url; anchor.target = '_blank'; anchor.rel = 'noreferrer'; return anchor;
}
async function render() {
  items = (await allItems()).filter(item => item.account === account).sort((a, b) => a.createdAt - b.createdAt);
  const stored = await chrome.storage.local.get(null);
  $('selected-count').textContent = Object.entries(stored).filter(([key, value]) => key.startsWith('selection:') && value.account === account).length;
  const stats = totals(items);
  $('saved').textContent = bytes(stats.smallerBy);
  $('progress').max = Math.max(1, stats.selected);
  $('progress').value = stats.verified;
  $('progress-label').textContent = stats.selected ? `${stats.verified} / ${stats.selected} verified` : 'Ready when you are';
  $('empty').hidden = items.length > 0;
  $('start').disabled = runner.busy || !$('metadata').checked || !items.some(item => ['selected', 'compressed', 'uploaded'].includes(item.state));
  $('pause').disabled = !runner.busy;
  $('connect').disabled = runner.busy;
  $('import').disabled = runner.busy;
  $('cleanup').disabled = runner.busy || !items.some(item => eligible(item, account));
  const list = $('items'); list.replaceChildren();
  for (const [index, item] of items.entries()) {
    const row = document.createElement('article'); row.className = 'item';
    const number = document.createElement('span'); number.className = 'item-index'; number.textContent = String(index + 1).padStart(2, '0');
    const details = document.createElement('div');
    const title = document.createElement('h3'); title.textContent = item.label || 'Selected photo';
    const state = document.createElement('span'); state.className = 'state'; state.textContent = item.state.replaceAll('-', ' ');
    details.append(title, state);
    if (item.outputBytes) {
      const sizes = document.createElement('p'); sizes.textContent = `${bytes(item.originalBytes)} → ${bytes(item.outputBytes)} · ${item.width} × ${item.height}`; details.append(sizes);
    }
    if (item.error) { const error = document.createElement('p'); error.className = 'error'; error.textContent = item.error; details.append(error); }
    const actions = document.createElement('div'); actions.className = 'item-actions';
    actions.append(link('Original', item.originalURL));
    if (item.replacementURL) actions.append(link('Replacement', item.replacementURL));
    if (item.outputHash) actions.append(smallButton('Save compressed copy', async () => {
      const blob = await getBlob(`${item.id}:output`);
      if (!blob) throw new Error('Local copy is no longer available');
      const url = URL.createObjectURL(blob), anchor = document.createElement('a');
      anchor.href = url; anchor.download = outputName(item); anchor.click();
      setTimeout(() => URL.revokeObjectURL(url), 30000);
    }));
    if (['uploaded', 'upload-uncertain', 'review'].includes(item.state)) {
      actions.append(smallButton('Reconcile', () => runner.reconcile(item)));
      actions.append(smallButton('Link replacement', () => { linkedItem = item; $('replacement-url').value = ''; $('link-dialog').showModal(); }));
    }
    if (item.state === 'failed') actions.append(smallButton('Retry', () => edit(() => putItem({...item, state: item.outputHash ? 'compressed' : 'selected', error: ''}))));
    // Removing local records never changes anything in Google Photos.
    actions.append(smallButton('Forget locally', async () => {
      if (!confirm('Remove this local batch record and temporary files? This does not change Google Photos.')) return;
      await edit(() => forgetItem(item.id));
    }));
    details.append(actions); row.append(number, details); list.append(row);
  }
}
$('connect').onclick = () => action(connect);
$('import').onclick = () => action(importSelection);
$('quality').oninput = () => { $('quality-value').textContent = `${$('quality').value}%`; };
$('metadata').onchange = () => render();
$('start').onclick = () => action(async () => {
  if (!$('metadata').checked) throw new Error('Review the replacement notice first');
  await runner.process(items.filter(item => ['selected', 'compressed', 'uploaded'].includes(item.state)), Number($('quality').value) / 100);
  notice(runner.paused ? 'Paused after the current safe operation. Resume when ready.' : 'Batch finished. Review results and reconcile any uncertain uploads. Originals stay until you approve cleanup.');
});
$('pause').onclick = () => { runner.paused = true; notice('Pausing after the current safe operation…'); };
$('open-tab').onclick = () => action(async () => {
  if (runner.busy) throw new Error('Pause this panel before opening a workspace tab');
  await chrome.tabs.create({url: chrome.runtime.getURL('app.html')});
});
$('cleanup').onclick = () => {
  cleanupIds = items.filter(item => eligible(item, account)).map(item => item.id);
  $('cleanup-summary').textContent = `${cleanupIds.length} original${cleanupIds.length === 1 ? '' : 's'} in ${account}. Each replacement and original will be downloaded and rechecked first.`;
  $('cleanup-dialog').showModal();
};
$('cancel-cleanup').onclick = () => $('cleanup-dialog').close();
$('confirm-cleanup').onclick = () => {
  $('cleanup-dialog').close();
  action(async () => {
    const fresh = (await allItems()).filter(item => cleanupIds.includes(item.id) && eligible(item, account));
    if (fresh.length !== cleanupIds.length) throw new Error('Batch changed. Review cleanup again.');
    await runner.cleanup(fresh);
    notice('Cleanup finished or stopped for review. See each photo’s status. Trash has not been emptied.');
  });
};
$('cancel-link').onclick = () => $('link-dialog').close();
$('confirm-link').onclick = () => action(async () => {
  const url = $('replacement-url').value.trim(); photoIdentity(url);
  $('link-dialog').close(); await runner.reconcile(linkedItem, url);
});
chrome.storage.onChanged.addListener(() => render().catch(error => notice(error.message)));
window.addEventListener('beforeunload', event => { if (runner.busy) { event.preventDefault(); event.returnValue = ''; } });
await runner.recover();
await render();
await action(connect);

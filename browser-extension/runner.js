import {allItems, putItem, getBlob, putBlob} from './db.js';
import {assertAccount, eligible, outputName, photoIdentity, recover, sha256} from './core.js';
import {compress, inspect} from './image.js';
import {command, download, navigate, upload} from './transport.js';

export class Runner {
  constructor(onChange) { this.onChange = onChange; this.paused = false; this.busy = false; }
  async save(item) { await putItem(item); await this.onChange(); }
  async exclusive(task) {
    if (this.busy) throw new Error('A batch is already running');
    return navigator.locks.request('pichalite-photo-runner', {ifAvailable: true}, async lock => {
      if (!lock) throw new Error('PichaLite is running in another panel or tab');
      this.busy = true; this.paused = false;
      try { await task(); } finally { this.busy = false; await this.onChange(); }
    });
  }
  async recover() {
    await navigator.locks.request('pichalite-photo-runner', {ifAvailable: true}, async lock => {
      if (!lock) return;
      for (const item of await allItems()) {
        const next = recover(item);
        if (next !== item) await putItem(next);
      }
    });
  }
  async workTab(account, route) {
    const tab = await chrome.tabs.create({url: `https://photos.google.com${route || ''}/`, active: false});
    this.tabId = tab.id;
    this.account = account;
    return tab.id;
  }
  async process(items, quality) {
    return this.exclusive(async () => {
      const first = items[0];
      if (!first) return;
      await this.workTab(first.account, first.route);
      for (let item of items) {
        if (this.paused) break;
        assertAccount(first.account, item.account);
        try {
          if (item.state === 'selected') {
            item = {...item, state: 'downloading', error: ''}; await this.save(item);
            await navigate(this.tabId, item.originalURL, item.account);
            const original = await download(this.tabId, item.originalURL, item.account);
            inspect(new Uint8Array(await original.arrayBuffer()));
            await putBlob(`${item.id}:original`, original);
            item = {...item, state: 'compressing', originalBytes: original.size}; await this.save(item);
            const result = await compress(original, quality);
            await putBlob(`${item.id}:output`, result.output);
            item = {...item, ...result, output: undefined, outputBytes: result.output.size, quality, state: 'compressed'};
            await this.save(item);
          }
          if (this.paused) break;
          if (item.state === 'compressed') {
            const recentURL = `https://photos.google.com${item.route || ''}/recentlyadded`;
            const before = await navigate(this.tabId, recentURL, item.account);
            const output = await getBlob(`${item.id}:output`);
            if (!output || await sha256(output) !== item.outputHash) throw new Error('Local compressed file is missing or changed');
            // Save BEFORE submission; an interrupted upload is reconciled, never blindly retried.
            item = {...item, state: 'uploading', beforeLinks: before.links, uploadStartedAt: Date.now()}; await this.save(item);
            await upload(this.tabId, item, output, outputName(item));
            item = {...item, state: 'uploaded'}; await this.save(item);
          }
          if (this.paused) break;
          if (['uploaded', 'upload-uncertain'].includes(item.state)) await this.verify(item);
        } catch (error) {
          const state = item.state === 'uploading' ? 'upload-uncertain' : item.state === 'verifying' ? 'uploaded' : ['uploaded', 'upload-uncertain', 'compressed'].includes(item.state) ? item.state : 'failed';
          await this.save({...item, state, error: error.message});
        }
      }
    });
  }
  async verify(item, explicitURL) {
    if (!item.outputHash) throw new Error('No compressed output to verify');
    const recentURL = `https://photos.google.com${item.route || ''}/recentlyadded`;
    const recent = await navigate(this.tabId, recentURL, item.account);
    const candidates = explicitURL ? [explicitURL] : recent.links.filter(url => !(item.beforeLinks || []).includes(url) && photoIdentity(url) !== photoIdentity(item.originalURL)).slice(0, 12);
    await this.save({...item, state: 'verifying'});
    for (const url of candidates) {
      if (photoIdentity(url) === photoIdentity(item.originalURL)) continue;
      await navigate(this.tabId, url, item.account);
      const copy = await download(this.tabId, url, item.account);
      const hash = await sha256(copy);
      if (hash !== item.outputHash) continue;
      const info = inspect(new Uint8Array(await copy.arrayBuffer()));
      if (info.width !== item.width || info.height !== item.height) continue;
      const verified = {...item, state: 'verified', replacementURL: url, verification: {hash, account: item.account, at: Date.now()}, error: ''};
      await this.save(verified);
      return verified;
    }
    throw new Error('No exact replacement found among recent uploads. Keep the original. Use Reconcile, or provide the replacement’s individual photo URL.');
  }
  async reconcile(item, explicitURL) {
    return this.exclusive(async () => {
      await this.workTab(item.account, item.route);
      try { await this.verify(item, explicitURL); }
      catch (error) { await this.save({...item, error: error.message}); throw error; }
    });
  }
  async cleanup(items) {
    return this.exclusive(async () => {
      if (!items.length) return;
      await this.workTab(items[0].account, items[0].route);
      for (let item of items) {
        if (this.paused) break;
        if (!eligible(item, items[0].account)) throw new Error('Item has no verified replacement');
        try {
          // Re-verify both ends immediately before every destructive operation.
          await navigate(this.tabId, item.replacementURL, item.account);
          const replacement = await download(this.tabId, item.replacementURL, item.account);
          if (await sha256(replacement) !== item.outputHash) throw new Error('Replacement changed or is unavailable. Original kept.');
          await navigate(this.tabId, item.originalURL, item.account);
          const original = await download(this.tabId, item.originalURL, item.account);
          if (await sha256(original) !== item.originalHash) throw new Error('Original changed since compression. Original kept.');
          if (this.paused) break;
          item = {...item, state: 'trashing', error: ''}; await this.save(item);
          await command(this.tabId, 'trash', {expectedURL: item.originalURL, account: item.account});
          await this.save({...item, state: 'trashed', trashedAt: Date.now()});
        } catch (error) {
          await this.save({...item, state: item.state === 'trashing' ? 'trash-uncertain' : 'review', error: error.message});
          // Stop the batch on any cleanup uncertainty rather than continuing destructive actions.
          break;
        }
      }
    });
  }
}

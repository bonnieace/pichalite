(() => {
  if (globalThis.__pichaliteInstalled) return;
  globalThis.__pichaliteInstalled = true;
  const PHOTO = /^\/(?:u\/\d+\/)?photo\/([A-Za-z0-9_-]+)$/;
  const uploads = new Map();
  let selecting = false;
  const sleep = ms => new Promise(r => setTimeout(r, ms));
  const visible = el => !!el && !!el.getClientRects().length && !el.closest('[aria-hidden="true"]');
  const label = el => (el.getAttribute('aria-label') || el.getAttribute('title') || el.innerText || el.textContent || '').replace(/\s+/g, ' ').trim();
  const controls = () => [...document.querySelectorAll('button,[role="button"],[role="menuitem"],a')].filter(visible);
  function unique(pattern, root = document) {
    const matches = controls().filter(el => root.contains(el) && pattern.test(label(el)));
    // Nested labels may expose the same control twice; never guess between separate controls.
    const outer = matches.filter(el => !matches.some(other => other !== el && other.contains(el)));
    if (outer.length !== 1) throw new Error(`Expected one ${pattern} control; found ${outer.length}. This preview supports English Google Photos controls.`);
    return outer[0];
  }
  async function until(fn, timeout = 12000) {
    const end = Date.now() + timeout;
    while (Date.now() < end) { const value = fn(); if (value) return value; await sleep(150); }
    throw new Error('Google Photos did not reach the expected state. Originals have not been automatically retried.');
  }
  function account() {
    const nodes = [...document.querySelectorAll('[aria-label*="Google Account"],a[href*="accounts.google.com/SignOutOptions"]')];
    const addresses = new Set(nodes.filter(visible).map(el => label(el).match(/[A-Z0-9._%+-]+@[A-Z0-9.-]+\.[A-Z]{2,}/i)?.[0]?.toLowerCase()).filter(Boolean));
    if (addresses.size !== 1) throw new Error('Could not identify the active Google account. Open the Google account menu, then try again.');
    return [...addresses][0];
  }
  function route() { return location.pathname.match(/^\/u\/\d+/)?.[0] || ''; }
  function photoURL(value) {
    const url = new URL(value, location.origin);
    if (url.origin !== location.origin || !PHOTO.test(url.pathname) || url.search || url.hash) return null;
    return url.origin + url.pathname;
  }
  function guard(message, requirePhoto = true) {
    if (account() !== message.account) throw new Error('Active Google account changed');
    if (requirePhoto && (!photoURL(location.href) || location.href !== message.expectedURL)) throw new Error('The original photo changed. Cleanup stopped.');
  }
  function links() {
    return [...new Set([...document.querySelectorAll('a[href]')].map(el => photoURL(el.href)).filter(Boolean))];
  }
  async function add(url, name) {
    const identity = account();
    const key = `selection:${identity}:${PHOTO.exec(new URL(url).pathname)[1]}`;
    const stored = await chrome.storage.local.get(key);
    if (stored[key]) await chrome.storage.local.remove(key);
    else await chrome.storage.local.set({[key]: {originalURL: url, account: identity, route: route(), label: name || 'Selected photo'}});
    const selection = await chrome.storage.local.get(null);
    count.textContent = `${Object.keys(selection).filter(k => k.startsWith(`selection:${identity}:`)).length} selected`;
  }
  // A shadow root keeps PichaLite's controls independent of Google's styles.
  const host = document.createElement('div');
  host.id = 'pichalite-selection-toolbar';
  host.style.cssText = 'position:fixed;bottom:22px;left:50%;transform:translateX(-50%);z-index:2147483647';
  const root = host.attachShadow({mode: 'closed'});
  root.innerHTML = `<style>:host{font:14px system-ui}section{display:flex;gap:9px;align-items:center;max-width:95vw;flex-wrap:wrap;background:#112a24;color:#fff;padding:10px 14px;border-radius:18px;box-shadow:0 5px 28px #0004}button{font:inherit;border:0;border-radius:11px;padding:10px;background:#d1fa8c;color:#112a24;cursor:pointer}span{max-width:190px}button[aria-pressed=true]{background:#fff}</style><section><b>PichaLite</b><button id="select" aria-pressed="false">Select photos</button><button id="current">Add open photo</button><button id="open">Open PichaLite</button><span id="count" role="status">Local compression</span></section>`;
  const count = root.getElementById('count');
  root.getElementById('open').onclick = () => chrome.runtime.sendMessage({type: 'open-panel'}).then(result => {
    if (!result?.ok) count.textContent = 'Click the PichaLite extension icon to open the panel';
  }).catch(() => { count.textContent = 'Click the PichaLite extension icon to open the panel'; });
  root.getElementById('select').onclick = event => {
    selecting = !selecting;
    event.target.setAttribute('aria-pressed', String(selecting));
    event.target.textContent = selecting ? 'Finish selecting' : 'Select photos';
    count.textContent = selecting ? 'Click photos to add them' : 'Open the PichaLite side panel';
  };
  root.getElementById('current').onclick = () => {
    const url = photoURL(location.href);
    if (!url) { count.textContent = 'Open a photo first'; return; }
    add(url, document.title).catch(error => { count.textContent = error.message; });
  };
  document.documentElement.append(host);
  document.addEventListener('click', event => {
    if (!selecting) return;
    const anchor = event.target.closest?.('a[href]');
    const url = anchor && photoURL(anchor.href);
    if (!url) return;
    event.preventDefault(); event.stopImmediatePropagation();
    add(url, anchor.getAttribute('aria-label') || anchor.querySelector('img')?.alt).catch(error => { count.textContent = error.message; });
  }, true);

  async function command(message) {
    if (message.command === 'describe') return {account: account(), url: location.href, route: route(), links: links()};
    guard(message, !['upload-start', 'upload-chunk', 'upload-commit', 'recent'].includes(message.command));
    if (message.command === 'recent') return {links: links()};
    if (message.command === 'download') {
      const menus = controls().filter(el => /^More options(?:\b|$)|^More$/i.test(label(el)));
      if (menus.length !== 1) throw new Error('Open one photo with its More options button visible.');
      menus[0].click();
      const download = await until(() => { try { return unique(/^Download(?:\s|$)/i); } catch { return null; } });
      const href = download instanceof HTMLAnchorElement ? download.href : null;
      if (href && /^https:\/\//.test(href)) {
        document.dispatchEvent(new KeyboardEvent('keydown', {key: 'Escape', bubbles: true}));
        return {url: href};
      }
      download.click();
      return {};
    }
    if (message.command === 'upload-start') {
      if (uploads.size) throw new Error('An upload is already being prepared');
      uploads.set(message.id, {chunks: [], size: 0, name: message.name, type: 'image/jpeg'});
      return {};
    }
    if (message.command === 'upload-chunk') {
      const upload = uploads.get(message.id);
      if (!upload) throw new Error('Upload preparation expired');
      const bytes = Uint8Array.from(atob(message.chunk), c => c.charCodeAt(0));
      upload.size += bytes.length;
      if (upload.size > 40 * 1024 * 1024) { uploads.delete(message.id); throw new Error('Upload is too large'); }
      upload.chunks.push(bytes);
      return {};
    }
    if (message.command === 'upload-commit') {
      const upload = uploads.get(message.id);
      if (!upload) throw new Error('Upload preparation expired');
      try {
        let inputs = [...document.querySelectorAll('input[type=file]')].filter(el => !el.accept || /image|\.jp/i.test(el.accept));
        if (inputs.length !== 1) {
          unique(/^(Upload|Add photos|Create and add photos)$/i).click();
          const importControl = await until(() => {
            const found = controls().filter(el => /^(Computer|From computer|Import photos from your computer)$/i.test(label(el)));
            return found.length === 1 && found[0];
          });
          importControl.click();
          inputs = await until(() => {
            const found = [...document.querySelectorAll('input[type=file]')].filter(el => !el.accept || /image|\.jp/i.test(el.accept));
            return found.length === 1 && found;
          });
        }
        guard(message, false);
        const transfer = new DataTransfer();
        transfer.items.add(new File(upload.chunks, upload.name, {type: upload.type}));
        const finished = new Promise((resolve, reject) => {
          const observer = new MutationObserver(records => {
            const text = records.flatMap(record => [...record.addedNodes].map(node => node.textContent || '').concat(record.type === 'characterData' ? [record.target.textContent || ''] : [])).join(' ');
            if (/upload complete|\b1 (?:photo|item) uploaded\b/i.test(text)) { observer.disconnect(); clearTimeout(timer); resolve(true); }
          });
          const timer = setTimeout(() => { observer.disconnect(); reject(new Error('Upload completion was not confirmed. Reconcile recent uploads before retrying.')); }, 90000);
          observer.observe(document.body, {childList: true, characterData: true, subtree: true});
        });
        inputs[0].files = transfer.files;
        inputs[0].dispatchEvent(new Event('change', {bubbles: true}));
        await finished;
        return {submitted: true};
      } finally { uploads.delete(message.id); }
    }
    if (message.command === 'trash') {
      // Single-photo viewer only. Never operate on the library's native multiselect.
      unique(/^(Delete|Move to trash|Move to bin)$/i).click();
      const dialog = await until(() => [...document.querySelectorAll('[role=dialog]')].find(visible));
      if (!/(trash|bin)/i.test(dialog.textContent) || /permanently|delete forever/i.test(dialog.textContent)) throw new Error('Unexpected deletion dialog. Nothing confirmed.');
      guard(message);
      const confirm = unique(/^(Move to trash|Move to bin)$/i, dialog);
      // Require a new success notification caused by this confirmation, not a stale toast.
      const success = new Promise((resolve, reject) => {
        const observer = new MutationObserver(records => {
          for (const record of records) {
            const texts = [...record.addedNodes].map(node => node.textContent || '');
            if (record.type === 'characterData') texts.push(record.target.textContent || '');
            if (texts.some(text => /^\s*(Moved to (?:trash|bin)|1 (?:photo|item) (?:moved to|deleted))\b/i.test(text))) { observer.disconnect(); clearTimeout(timer); resolve(true); return; }
          }
        });
        const timer = setTimeout(() => { observer.disconnect(); reject(new Error('Cleanup was submitted but not confirmed. Check Google Photos trash.')); }, 10000);
        observer.observe(document.body, {childList: true, characterData: true, subtree: true});
      });
      confirm.click();
      await success;
      return {trashed: true};
    }
    throw new Error('Unknown PichaLite command');
  }
  chrome.runtime.onMessage.addListener((message, sender, respond) => {
    if (sender.id !== chrome.runtime.id || message.type !== 'pichalite-command') return;
    command(message).then(value => respond({ok: true, value}), error => respond({ok: false, error: error.message}));
    return true;
  });
})();

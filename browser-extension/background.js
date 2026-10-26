import {mediaURL} from './core.js';

chrome.sidePanel.setPanelBehavior({openPanelOnActionClick: true}).catch(console.error);
const pending = new Map();
const filters = {urls: ['https://photos.google.com/*', 'https://*.googleusercontent.com/*']};
// Observe only an explicitly armed original-download operation in its exact tab.
chrome.webRequest.onHeadersReceived.addListener(details => {
  const waiter = pending.get(details.tabId);
  if (!waiter || details.statusCode < 200 || details.statusCode >= 300) return;
  const disposition = details.responseHeaders?.find(h => h.name.toLowerCase() === 'content-disposition')?.value || '';
  if (!/attachment/i.test(disposition)) return;
  try { waiter.resolve(mediaURL(details.url)); } catch (error) { waiter.reject(error); }
}, filters, ['responseHeaders']);

chrome.runtime.onMessage.addListener((message, sender, respond) => {
  if (message.type === 'open-panel' && sender.id === chrome.runtime.id && sender.tab?.url?.startsWith('https://photos.google.com/')) {
    chrome.sidePanel.open({tabId: sender.tab.id}).then(() => respond({ok: true}), error => respond({ok: false, error: error.message}));
    return true;
  }
  // Content scripts cannot arm downloads or use this worker as a network proxy.
  if (sender.id !== chrome.runtime.id || !sender.url?.startsWith(chrome.runtime.getURL(''))) return;
  if (message.type !== 'download-original') return;
  (async () => {
    if (pending.has(message.tabId)) throw new Error('Another download is already running');
    let timeout;
    const captured = new Promise((resolve, reject) => {
      pending.set(message.tabId, {resolve, reject});
      timeout = setTimeout(() => reject(new Error('Could not capture the original download. Google Photos may have changed its download controls.')), 18000);
    });
    // Attach a handler immediately so a slow page cannot create an unhandled rejection.
    captured.catch(() => {});
    try {
      const result = await chrome.tabs.sendMessage(message.tabId, {type: 'pichalite-command', command: 'download', expectedURL: message.url, account: message.account});
      if (!result?.ok) throw new Error(result?.error || 'Google Photos did not respond');
      return {url: result.value?.url ? mediaURL(result.value.url) : await captured};
    } finally { clearTimeout(timeout); pending.delete(message.tabId); }
  })().then(value => respond({ok: true, value}), error => respond({ok: false, error: error.message}));
  return true;
});

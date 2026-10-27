export const MAX_BYTES = 40 * 1024 * 1024;
export const MAX_PIXELS = 40_000_000;
export const TRANSIENT = new Set(['downloading', 'compressing']);
export function photoIdentity(value) {
  const u = new URL(value);
  if (u.origin !== 'https://photos.google.com' || u.username || u.password || u.search || u.hash) throw new Error('Invalid photo link');
  const match = u.pathname.match(/^\/(?:u\/\d+\/)?photo\/([A-Za-z0-9_-]+)$/);
  if (!match) throw new Error('Open a photo in your own library first. Shared and album links are excluded in this preview.');
  return match[1];
}
export function mediaURL(value) {
  const u = new URL(value);
  if (u.protocol !== 'https:' || u.username || u.password || !(u.hostname === 'photos.google.com' || u.hostname.endsWith('.googleusercontent.com'))) throw new Error('Unexpected download destination');
  return u.href;
}
export function assertAccount(expected, actual) {
  if (!expected || !actual || expected !== actual) throw new Error('Google account changed or could not be verified. Return to the original account.');
}
export function eligible(item, account) {
  try {
    return item.state === 'verified' && item.account === account &&
      item.originalHash && item.outputHash && item.verification?.hash === item.outputHash &&
      item.verification?.account === account &&
      photoIdentity(item.replacementURL) !== photoIdentity(item.originalURL) &&
      item.outputBytes > 0 && item.outputBytes < item.originalBytes;
  } catch { return false; }
}
export function recover(item) {
  if (['uploading', 'verifying'].includes(item.state)) return {...item, state: 'upload-uncertain', error: 'Upload or verification was interrupted. Reconcile recent uploads before retrying.'};
  if (item.state === 'trashing') return {...item, state: 'trash-uncertain', error: 'Cleanup was interrupted. Check Google Photos trash before taking further action.'};
  if (TRANSIENT.has(item.state)) return {...item, state: item.outputHash ? 'compressed' : 'selected', error: 'Interrupted; ready to resume.'};
  return item;
}
export function totals(items) {
  return items.reduce((out, i) => {
    out.selected++;
    if (i.outputBytes && i.originalBytes) out.smallerBy += Math.max(0, i.originalBytes - i.outputBytes);
    if (i.state === 'verified' || i.state === 'trashed') out.verified++;
    if (i.state === 'trashed') out.trashed++;
    return out;
  }, {selected: 0, verified: 0, trashed: 0, smallerBy: 0});
}
export async function sha256(blob) {
  return [...new Uint8Array(await crypto.subtle.digest('SHA-256', await blob.arrayBuffer()))].map(x => x.toString(16).padStart(2, '0')).join('');
}
export function outputName(item) { return `PichaLite-${item.id}.jpg`; }

// Firefox/Safari expose the Promise API through browser; older Chromium uses chrome.
export const ext = globalThis.browser?.runtime ? globalThis.browser : globalThis.chrome;

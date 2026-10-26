import test from 'node:test';
import assert from 'node:assert/strict';
let listener, headers, respondToTab;
globalThis.chrome = {
  runtime: {id: 'pichalite-test', getURL: path => `chrome-extension://pichalite-test/${path}`, onMessage: {addListener: fn => {listener = fn;}}},
  sidePanel: {setPanelBehavior: async () => {}, open: async () => {}},
  webRequest: {onHeadersReceived: {addListener: fn => {headers = fn;}}},
  tabs: {sendMessage: (...args) => respondToTab(...args)}
};
await import('../background.js');
const sender = {id: 'pichalite-test', url: 'chrome-extension://pichalite-test/app.html'};
function request(message) { return new Promise(resolve => listener(message, sender, resolve)); }
test('download capture ignores other tabs and non-attachment responses', async () => {
  respondToTab = async tabId => {
    headers({tabId: 999, statusCode: 200, url: 'https://lh3.googleusercontent.com/wrong', responseHeaders: [{name:'Content-Disposition',value:'attachment'}]});
    headers({tabId, statusCode: 200, url: 'https://lh3.googleusercontent.com/thumbnail', responseHeaders: [{name:'Content-Type',value:'image/jpeg'}]});
    headers({tabId, statusCode: 200, url: 'https://lh3.googleusercontent.com/original', responseHeaders: [{name:'Content-Disposition',value:'attachment; filename="photo.jpg"'}]});
    return {ok:true,value:{}};
  };
  const result = await request({type:'download-original',tabId:12,url:'https://photos.google.com/photo/a',account:'a@example.test'});
  assert.equal(result.ok,true);assert.equal(result.value.url,'https://lh3.googleusercontent.com/original');
});
test('worker rejects an unexpected direct download destination', async () => {
  respondToTab = async () => ({ok:true,value:{url:'https://evil.test/image.jpg'}});
  const result = await request({type:'download-original',tabId:12});
  assert.equal(result.ok,false);assert.match(result.error,/Unexpected download destination/);
});
test('a content script cannot arm privileged downloads', () => {
  let replied=false;
  assert.equal(listener({type:'download-original',tabId:12},{id:'pichalite-test',url:'https://photos.google.com/'},()=>{replied=true}),undefined);
  assert.equal(replied,false);
});
test('failed page commands release the per-tab download lock', async () => {
  respondToTab = async () => ({ok:false,error:'Wrong account'});
  assert.match((await request({type:'download-original',tabId:12})).error,/Wrong account/);
  respondToTab = async () => ({ok:true,value:{url:'https://lh3.googleusercontent.com/next'}});
  assert.equal((await request({type:'download-original',tabId:12})).ok,true);
});

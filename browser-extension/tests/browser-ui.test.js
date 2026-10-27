import test from 'node:test';
import assert from 'node:assert/strict';
import {configureAction, openUI} from '../browser-ui.js';
test('Chromium uses its side panel with the originating tab', async () => {
  let options;
  const api={sidePanel:{open:async value=>{options=value;}}};
  await openUI(api,{id:4});assert.deepEqual(options,{tabId:4});
});
test('Firefox uses its native sidebar without depending on chrome.sidePanel', async () => {
  let opens=0;
  const api={sidebarAction:{open:async()=>{opens++;}}};
  await openUI(api,{id:4});assert.equal(opens,1);
});
test('Safari and browsers without sidebar APIs open a workspace tab', async () => {
  let options;
  const api={runtime:{getURL:path=>'test-extension://id/'+path},tabs:{create:async value=>{options=value;}}};
  await openUI(api,{id:4});assert.deepEqual(options,{url:'test-extension://id/app.html'});
});
test('toolbar action registers and invokes sidebar synchronously for user activation', async () => {
  let listener,opened=false;
  const api={sidebarAction:{open:()=>{opened=true;return Promise.resolve();}},action:{onClicked:{addListener:value=>{listener=value;}}}};
  configureAction(api);listener({id:4});assert.equal(opened,true);
});

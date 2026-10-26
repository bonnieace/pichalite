import test from 'node:test';
import assert from 'node:assert/strict';
import {execFileSync} from 'node:child_process';
import {readFileSync} from 'node:fs';
test('target packages use native browser manifests and retain the same safety code',()=>{
  execFileSync('python3',['scripts/package.py']);
  const load=target=>JSON.parse(readFileSync(`dist/${target}/manifest.json`));
  const chromium=load('chromium'),firefox=load('firefox'),safari=load('safari');
  assert(chromium.permissions.includes('sidePanel'));assert(chromium.side_panel);
  assert(!firefox.permissions.includes('sidePanel'));assert(!firefox.side_panel);
  assert.deepEqual(firefox.background,{scripts:['background.js'],type:'module'});
  assert(firefox.sidebar_action);assert.equal(firefox.browser_specific_settings.gecko.strict_min_version,'142.0');
  assert(!safari.side_panel);assert(!safari.permissions.includes('unlimitedStorage'));
  for(const target of ['chromium','firefox','safari']) {
    assert.equal(readFileSync(`dist/${target}/runner.js`,'utf8'),readFileSync('runner.js','utf8'));
    assert.equal(readFileSync(`dist/${target}/core.js`,'utf8'),readFileSync('core.js','utf8'));
    const manifest=load(target);
    for(const [size, icon] of Object.entries(manifest.icons)) {
      const data=readFileSync(`dist/${target}/${icon}`);
      assert.equal(data.readUInt32BE(16),Number(size));
      assert.equal(data.readUInt32BE(20),Number(size));
    }
  }
});

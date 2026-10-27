import {readdirSync, readFileSync} from 'node:fs';
import {execFileSync} from 'node:child_process';
import assert from 'node:assert/strict';
const manifest = JSON.parse(readFileSync('manifest.json'));
assert.equal(manifest.manifest_version, 3);
assert(!manifest.permissions.includes('debugger'));
assert(!manifest.host_permissions.includes('<all_urls>'));
for (const file of readdirSync('.').filter(file => file.endsWith('.js'))) execFileSync(process.execPath, ['--check', file]);
for (const file of ['app.html', 'app.css', ...readdirSync('.').filter(file => file.endsWith('.js'))]) {
  const text = readFileSync(file, 'utf8');
  assert(!/\beval\s*\(|\bnew Function\s*\(/.test(text), `${file}: dynamic code`);
}
console.log('Manifest, JavaScript syntax and packaged-code checks passed.');

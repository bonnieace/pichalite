import test from 'node:test';
import assert from 'node:assert/strict';
import {assertAccount, eligible, mediaURL, photoIdentity, recover, sha256, totals} from '../core.js';

const originalURL = 'https://photos.google.com/photo/original1';
const replacementURL = 'https://photos.google.com/u/2/photo/replacement2';
const good = {state: 'verified', account: 'test@example.test', originalURL, replacementURL, originalHash: 'old', outputHash: 'new', originalBytes: 200, outputBytes: 100, verification: {hash: 'new', account: 'test@example.test'}};
test('cleanup requires a different photo and matching account and verified output hash', () => {
  assert(eligible(good, good.account));
  for (const bad of [
    {...good, state: 'uploaded'}, {...good, state: 'upload-uncertain'}, {...good, account: 'other@example.test'},
    {...good, replacementURL: originalURL}, {...good, replacementURL: originalURL.replace('/photo/', '/u/0/photo/')},
    {...good, outputHash: 'different'}, {...good, verification: null}, {...good, originalHash: ''},
    {...good, outputBytes: 200}, {...good, outputBytes: 0}, {...good, replacementURL: 'https://evil.test/photo/123'}
  ]) assert(!eligible(bad, good.account));
});
test('photo links reject shared links, lookalike hosts, query parameters and credentials', () => {
  assert.equal(photoIdentity(originalURL), 'original1');
  assert.equal(photoIdentity(replacementURL), 'replacement2');
  for (const url of ['https://photos.google.com.evil.test/photo/a', 'https://user:password@photos.google.com/photo/a', 'https://photos.google.com/share/a', `${originalURL}?x=1`, `${originalURL}#x`, 'https://photos.google.com/album/a/photo/b']) assert.throws(() => photoIdentity(url));
});
test('download destinations are restricted to Photos and its media hosts', () => {
  assert.equal(mediaURL('https://lh3.googleusercontent.com/file'), 'https://lh3.googleusercontent.com/file');
  for (const url of ['http://photos.google.com/file', 'https://evilgoogleusercontent.com/file', 'https://googleusercontent.com.evil.test/file', 'https://user:pass@photos.google.com/file']) assert.throws(() => mediaURL(url));
});
test('account guard fails closed for unknown and changed accounts', () => {
  assertAccount('a', 'a');
  for (const pair of [['a', 'b'], ['', ''], ['a', undefined]]) assert.throws(() => assertAccount(...pair));
});
test('restart never turns a possibly uploaded file into an automatic reupload', () => {
  for (const state of ['uploading', 'verifying']) assert.equal(recover({...good, state}).state, 'upload-uncertain');
  assert.equal(recover({...good, state: 'trashing'}).state, 'trash-uncertain');
  assert.equal(recover({...good, state: 'downloading', outputHash: undefined}).state, 'selected');
  assert.equal(recover({...good, state: 'compressing'}).state, 'compressed');
  assert.equal(recover(good), good);
});
test('file savings do not claim unverified quota savings or double-count cleanup', () => {
  assert.deepEqual(totals([good, {...good, state: 'trashed'}, {state: 'selected'}]), {selected: 3, verified: 2, trashed: 1, smallerBy: 200});
});
test('SHA-256 hashes bytes rather than names', async () => {
  assert.equal(await sha256(new Blob(['abc'])), 'ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad');
});

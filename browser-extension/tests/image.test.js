import test from 'node:test';
import assert from 'node:assert/strict';
import {attachExif, correctedExif, inspect} from '../image.js';
function jpeg(width = 800, height = 600) {
  return Uint8Array.from([255,216,255,192,0,17,8,height >> 8,height & 255,width >> 8,width & 255,3,1,17,0,2,17,0,3,17,0,255,218]);
}
function png(type = 2, extraType) {
  const bytes = new Uint8Array(extraType ? 57 : 45), view = new DataView(bytes.buffer);
  bytes.set([137,80,78,71,13,10,26,10]); view.setUint32(8,13); bytes.set(new TextEncoder().encode('IHDR'),12);
  view.setUint32(16,800);view.setUint32(20,600);bytes[24]=8;bytes[25]=type;
  if (extraType) bytes.set(new TextEncoder().encode(extraType),37);
  bytes.set(new TextEncoder().encode('IEND'),extraType ? 49 : 37);return bytes;
}
function exif(little) {
  const bytes = new Uint8Array(80), view = new DataView(bytes.buffer);
  bytes.set([69,120,105,102,0,0]);bytes.set(new TextEncoder().encode(little ? 'II' : 'MM'),6);
  view.setUint16(8,42,little);view.setUint32(10,8,little);view.setUint16(14,3,little);
  for (const [index,tag,value] of [[0,0x112,6],[1,0x100,800],[2,0x101,600]]) {
    const p=16+index*12;view.setUint16(p,tag,little);view.setUint16(p+2,3,little);view.setUint32(p+4,1,little);view.setUint16(p+8,value,little);
  }
  view.setUint32(52,64,little);return bytes;
}
test('JPEG dimensions are read before allocating a decoded bitmap', () => {
  assert.deepEqual(inspect(jpeg()), {type:'image/jpeg',width:800,height:600,exif:null});
  assert.throws(() => inspect(jpeg(30000,30000)), /40 megapixel/);
  assert.throws(() => inspect(jpeg().slice(0,12)), /Truncated/);
});
test('opaque PNG is supported; alpha, indexed transparency and animation are skipped', () => {
  assert.equal(inspect(png()).type,'image/png');
  for (const type of [4,6]) assert.throws(() => inspect(png(type)),/Transparent/);
  for (const chunk of ['acTL','tRNS']) assert.throws(() => inspect(png(2,chunk)),/Animated or transparent/);
});
test('EXIF correction handles both byte orders, resets orientation and drops old thumbnail pointer', () => {
  for (const little of [true,false]) {
    const original=exif(little), result=correctedExif(original,600,800), view=new DataView(result.buffer);
    assert.equal(view.getUint16(24,little),1);assert.equal(view.getUint16(36,little),600);assert.equal(view.getUint16(48,little),800);
    assert.equal(view.getUint32(52,little),0);assert.equal(new DataView(original.buffer).getUint16(24,little),6);
    const file=attachExif(jpeg(600,800),result);assert.equal(inspect(file).exif.length,80);
  }
});
test('malformed EXIF is rejected rather than silently discarded', () => {
  const bad=exif(true);new DataView(bad.buffer).setUint32(10,90000,true);
  assert.throws(() => correctedExif(bad,10,10));
});

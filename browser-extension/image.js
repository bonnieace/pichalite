import {MAX_BYTES, MAX_PIXELS, sha256} from './core.js';

const ascii = bytes => String.fromCharCode(...bytes);
export function inspect(bytes) {
  const view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
  const out = {type: '', width: 0, height: 0, exif: null};
  if (bytes[0] === 0xff && bytes[1] === 0xd8) {
    out.type = 'image/jpeg';
    let offset = 2;
    while (offset + 4 <= bytes.length) {
      if (bytes[offset] !== 0xff) throw new Error('Invalid JPEG marker');
      const marker = bytes[offset + 1];
      if (marker === 0xda || marker === 0xd9) break;
      const length = view.getUint16(offset + 2);
      if (length < 2 || offset + 2 + length > bytes.length) throw new Error('Truncated JPEG');
      const data = bytes.slice(offset + 4, offset + 2 + length);
      if ([0xc0, 0xc1, 0xc2].includes(marker)) {
        out.height = view.getUint16(offset + 5); out.width = view.getUint16(offset + 7);
      }
      if (marker === 0xe1 && ascii(data.slice(0, 6)) === 'Exif\0\0') out.exif = data;
      if ((marker === 0xe2 && ascii(data.slice(0, 4)) === 'MPF\0') || (marker === 0xe1 && /MotionPhoto|MicroVideo|hdrgm:|HDRGainMap/.test(new TextDecoder().decode(data)))) throw new Error('Motion photos, multi-picture and HDR gain-map images are excluded in this preview');
      offset += 2 + length;
    }
  } else if (ascii(bytes.slice(0, 8)) === '\x89PNG\r\n\x1a\n') {
    out.type = 'image/png';
    if (bytes.length < 33) throw new Error('Truncated PNG');
    out.width = view.getUint32(16); out.height = view.getUint32(20);
    // Preserve transparency by refusing conversion to JPEG, rather than silently flattening it.
    if ([4, 6].includes(bytes[25])) throw new Error('Transparent PNGs are kept unchanged');
    let offset = 8;
    while (offset + 12 <= bytes.length) {
      const length = view.getUint32(offset), type = ascii(bytes.slice(offset + 4, offset + 8));
      if (offset + 12 + length > bytes.length) throw new Error('Truncated PNG chunk');
      if (type === 'acTL' || type === 'tRNS') throw new Error('Animated or transparent PNGs are kept unchanged');
      if (type === 'eXIf') {
        if (length > 65527) throw new Error('EXIF metadata is too large');
        out.exif = new Uint8Array(length + 6);
        out.exif.set([69, 120, 105, 102, 0, 0]); out.exif.set(bytes.subarray(offset + 8, offset + 8 + length), 6);
      }
      offset += 12 + length;
    }
  } else throw new Error('This preview supports ordinary JPEG and opaque PNG images only');
  if (!out.width || !out.height || out.width * out.height > MAX_PIXELS) throw new Error('Image exceeds the 40 megapixel limit or has invalid dimensions');
  return out;
}

export function correctedExif(input, width, height) {
  if (!input) return null;
  if (input.length > 65533) throw new Error('EXIF metadata is too large');
  const data = input.slice(), start = 6;
  const view = new DataView(data.buffer);
  const little = ascii(data.slice(start, start + 2)) === 'II';
  if (!little && ascii(data.slice(start, start + 2)) !== 'MM') throw new Error('Invalid EXIF byte order');
  const u16 = p => view.getUint16(start + p, little);
  const u32 = p => view.getUint32(start + p, little);
  if (u16(2) !== 42) throw new Error('Invalid EXIF header');
  const visited = new Set();
  function walk(offset, primary = false) {
    if (visited.has(offset) || visited.size > 10) throw new Error('Invalid EXIF directory');
    visited.add(offset);
    const count = u16(offset);
    if (start + offset + 2 + count * 12 + 4 > data.length) throw new Error('Truncated EXIF directory');
    for (let n = 0; n < count; n++) {
      const p = offset + 2 + n * 12, tag = u16(p), type = u16(p + 2), length = u32(p + 4);
      if (tag === 0x8769 && type === 4 && length === 1) walk(u32(p + 8));
      let value;
      if (tag === 0x0112 || tag === 0xa001) value = 1; // Pixels are already oriented and encoded in sRGB.
      if ([0x0100, 0xa002].includes(tag)) value = width;
      if ([0x0101, 0xa003].includes(tag)) value = height;
      if (value !== undefined && length === 1) {
        if (type === 3) view.setUint16(start + p + 8, value, little);
        if (type === 4) view.setUint32(start + p + 8, value, little);
      }
    }
    // Do not retain the old embedded thumbnail directory.
    if (primary) view.setUint32(start + offset + 2 + count * 12, 0, little);
  }
  walk(u32(4), true);
  return data;
}

export function attachExif(jpeg, exif) {
  if (!exif) return jpeg;
  const size = exif.length + 2;
  const out = new Uint8Array(jpeg.length + exif.length + 4);
  out.set(jpeg.slice(0, 2)); out.set([255, 225, size >> 8, size & 255], 2);
  out.set(exif, 6); out.set(jpeg.slice(2), 6 + exif.length);
  return out;
}

export async function compress(original, quality = 0.82) {
  if (original.size > MAX_BYTES) throw new Error('Image exceeds the 40 MB limit');
  const bytes = new Uint8Array(await original.arrayBuffer());
  const source = inspect(bytes);
  const bitmap = await createImageBitmap(new Blob([bytes], {type: source.type}), {imageOrientation: 'from-image'});
  try {
    const canvas = new OffscreenCanvas(bitmap.width, bitmap.height);
    const context = canvas.getContext('2d', {alpha: false, colorSpace: 'srgb'});
    context.drawImage(bitmap, 0, 0);
    const encoded = await canvas.convertToBlob({type: 'image/jpeg', quality});
    if (encoded.type !== 'image/jpeg') throw new Error('JPEG encoder is unavailable');
    const exif = correctedExif(source.exif, bitmap.width, bitmap.height);
    const output = new Blob([attachExif(new Uint8Array(await encoded.arrayBuffer()), exif)], {type: 'image/jpeg'});
    if (output.size >= original.size) throw new Error('Already efficient: the compressed copy would not be smaller');
    return {output, width: bitmap.width, height: bitmap.height, originalHash: await sha256(original), outputHash: await sha256(output), metadata: exif ? 'Embedded EXIF retained; orientation and dimensions corrected' : 'No embedded EXIF found; Google Photos dates and library attributes are not copied'};
  } finally { bitmap.close(); }
}

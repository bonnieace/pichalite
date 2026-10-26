let opening;
function database() {
  return opening ||= new Promise((resolve, reject) => {
    const request = indexedDB.open('pichalite-photos-v1', 1);
    request.onupgradeneeded = () => {
      request.result.createObjectStore('items', {keyPath: 'id'});
      request.result.createObjectStore('blobs');
    };
    request.onsuccess = () => resolve(request.result);
    request.onerror = () => reject(request.error);
  });
}
async function transact(store, mode, action) {
  const db = await database();
  return new Promise((resolve, reject) => {
    const tx = db.transaction(store, mode);
    const req = action(tx.objectStore(store));
    let result, failure;
    req.onsuccess = () => { result = req.result; };
    req.onerror = () => { failure = req.error; };
    tx.oncomplete = () => resolve(result);
    tx.onabort = tx.onerror = () => reject(tx.error || failure || new Error('Local storage write failed'));
  });
}
export const allItems = () => transact('items', 'readonly', s => s.getAll());
export const putItem = item => transact('items', 'readwrite', s => s.put({...item, updatedAt: Date.now()}));
export async function getBlob(key) {
  const stored = await transact('blobs', 'readonly', s => s.get(key));
  if (stored === undefined || stored instanceof Blob) return stored; // Read 0.1.0 batches too.
  if (!(stored.bytes instanceof ArrayBuffer)) throw new Error('Local image data is invalid');
  return new Blob([stored.bytes], {type: stored.type});
}
export async function putBlob(key, blob) {
  // ArrayBuffer persistence also works in WebKit contexts that cannot serialize
  // Blob backing files into IndexedDB. Materialize bytes before opening a tx.
  const stored = {bytes: await blob.arrayBuffer(), type: blob.type};
  return transact('blobs', 'readwrite', s => s.put(stored, key));
}
export async function forgetItem(id) {
  const db = await database();
  return new Promise((resolve, reject) => {
    const tx = db.transaction(['items', 'blobs'], 'readwrite');
    tx.objectStore('items').delete(id);
    tx.objectStore('blobs').delete(`${id}:original`);
    tx.objectStore('blobs').delete(`${id}:output`);
    tx.oncomplete = resolve;
    tx.onabort = tx.onerror = () => reject(tx.error);
  });
}

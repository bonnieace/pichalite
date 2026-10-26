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
    let result;
    req.onsuccess = () => { result = req.result; };
    tx.oncomplete = () => resolve(result);
    tx.onabort = tx.onerror = () => reject(tx.error || new Error('Local storage write failed'));
  });
}
export const allItems = () => transact('items', 'readonly', s => s.getAll());
export const putItem = item => transact('items', 'readwrite', s => s.put({...item, updatedAt: Date.now()}));
export const getBlob = key => transact('blobs', 'readonly', s => s.get(key));
export const putBlob = (key, blob) => transact('blobs', 'readwrite', s => s.put(blob, key));
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

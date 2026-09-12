export function saleStorageKey(eventId: number, name: string) {
  return `fs.${eventId}.${name}`;
}

export function getSaleValue(eventId: number, name: string) {
  return sessionStorage.getItem(saleStorageKey(eventId, name));
}

export function setSaleValue(eventId: number, name: string, value: string) {
  sessionStorage.setItem(saleStorageKey(eventId, name), value);
}

export function removeSaleValue(eventId: number, name: string) {
  sessionStorage.removeItem(saleStorageKey(eventId, name));
}

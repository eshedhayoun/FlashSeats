/**
 * Money from integer minor units, in the browser's locale. Amounts are computed by the server from
 * the catalogue (invariant 6); this only renders them.
 */
export function formatMoney(amountCents: number, currency: string): string {
  try {
    return new Intl.NumberFormat(undefined, { style: "currency", currency }).format(amountCents / 100);
  } catch {
    // An unknown currency code makes Intl throw; showing the number beats showing nothing.
    return `${(amountCents / 100).toFixed(2)} ${currency}`;
  }
}

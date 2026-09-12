export function Countdown({ expiresAt }: { expiresAt: string }) {
  return <span aria-live="polite">{expiresAt}</span>;
}

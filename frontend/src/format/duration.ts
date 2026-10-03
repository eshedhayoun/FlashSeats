/**
 * `M:SS`, or `H:MM:SS` from an hour up. Partial seconds round up, so a live second is never shown
 * as gone, and a negative remainder is zero: a timer reaching the end is a prompt to ask the server,
 * never a value to display below zero (FE_SPEC §0 rule 4).
 */
export function formatDuration(remainingMs: number): string {
  const totalSeconds = Math.max(0, Math.ceil(remainingMs / 1000));
  const hours = Math.floor(totalSeconds / 3600);
  const minutes = Math.floor((totalSeconds % 3600) / 60);
  const seconds = totalSeconds % 60;

  if (hours > 0) {
    return `${hours}:${String(minutes).padStart(2, "0")}:${String(seconds).padStart(2, "0")}`;
  }
  return `${minutes}:${String(seconds).padStart(2, "0")}`;
}

/** The same remainder in words, for assistive technology and for copy that must not rely on digits. */
export function describeDuration(remainingMs: number): string {
  const totalSeconds = Math.max(0, Math.ceil(remainingMs / 1000));
  if (totalSeconds === 0) return "no time remaining";

  const hours = Math.floor(totalSeconds / 3600);
  const minutes = Math.floor((totalSeconds % 3600) / 60);
  const seconds = totalSeconds % 60;
  const parts: string[] = [];
  if (hours > 0) parts.push(plural(hours, "hour"));
  if (minutes > 0) parts.push(plural(minutes, "minute"));
  if (seconds > 0 && hours === 0) parts.push(plural(seconds, "second"));
  return `${parts.join(" and ")} remaining`;
}

function plural(count: number, unit: string): string {
  return `${count} ${unit}${count === 1 ? "" : "s"}`;
}

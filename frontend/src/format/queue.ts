/** Above this, an exact position only invites refresh-hammering (FE_SPEC V2). */
export const EXACT_POSITION_LIMIT = 1_000;

const count = new Intl.NumberFormat();

/**
 * The position as a buyer reads it. Position 1 is the front of the line, so it is "You're next" —
 * never `#0`, and never `#1` beside a message that someone is ahead.
 */
export function formatPosition(position: number | null): string {
  if (position === null) return "—";
  if (position <= 1) return "You're next";
  if (position > EXACT_POSITION_LIMIT) return `${count.format(EXACT_POSITION_LIMIT)}+`;
  return `#${count.format(position)}`;
}

/** The same, for a screen reader: words, not a hash sign. */
export function describePosition(position: number | null): string {
  if (position === null) return "Finding your place in line";
  if (position <= 1) return "You're next in line";
  if (position > EXACT_POSITION_LIMIT) return `More than ${count.format(EXACT_POSITION_LIMIT)} people are ahead of you`;
  return `You are number ${count.format(position)} in line`;
}

/**
 * Rounded generously and never in seconds: a precise estimate that slips is worse than a vague one
 * that holds. `null` means the server has no estimate yet, and saying so beats inventing one.
 */
export function formatWait(seconds: number | null | undefined): string {
  if (seconds === null || seconds === undefined || !Number.isFinite(seconds)) {
    return "Estimating your wait…";
  }
  if (seconds < 60) return "Less than a minute to go";
  if (seconds < 90) return "About a minute to go";
  const minutes = Math.round(seconds / 60);
  if (minutes < 60) return `About ${minutes} minutes to go`;
  const hours = Math.round(minutes / 60);
  return hours === 1 ? "About an hour to go" : `About ${hours} hours to go`;
}

/**
 * How far through the line a buyer is, from the position they first saw. `null` when there is
 * nothing to measure against, which the progress bar shows as indeterminate rather than as 0 %.
 */
export function queueProgress(startPosition: number | null, position: number | null): number | null {
  if (position !== null && position <= 1) return 100;
  if (startPosition === null || position === null || startPosition <= 1) return null;
  const done = (startPosition - Math.max(position, 1)) / (startPosition - 1);
  return Math.round(Math.min(1, Math.max(0, done)) * 100);
}

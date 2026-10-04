import { useSyncExternalStore } from "react";
import { serverClock } from "./serverClock";

/*
 * ONE app-wide 1 Hz tick for every countdown (FE_SPEC §6): a timeout to the next second, then a
 * requestAnimationFrame, so it pauses with the tab instead of piling up. Every countdown recomputes
 * from `expiresAt` on each tick — nothing decrements a local counter, so a throttled background tab
 * is right again on the first tick after it wakes.
 */
const listeners = new Set<() => void>();
let tick = 0;
let scheduled = false;

function notify() {
  tick += 1;
  scheduled = false;
  listeners.forEach((listener) => listener());
  schedule();
}

function schedule() {
  if (scheduled || listeners.size === 0) return;
  scheduled = true;
  window.setTimeout(() => window.requestAnimationFrame(notify), 1000);
}

export function subscribeTick(listener: () => void) {
  listeners.add(listener);
  schedule();
  return () => {
    listeners.delete(listener);
  };
}

function getSnapshot() {
  return tick;
}

/** Re-renders the caller once a second. Keep it in the smallest component that shows time. */
export function useClockTick() {
  return useSyncExternalStore(subscribeTick, getSnapshot, getSnapshot);
}

/**
 * Whether `iso` has passed on the server's clock. Re-renders the caller only when the answer flips,
 * not every second, so a whole page can depend on it.
 */
export function useHasPassed(iso: string): boolean {
  const snapshot = () => serverClock.remainingMs(iso) <= 0;
  return useSyncExternalStore(subscribeTick, snapshot, snapshot);
}

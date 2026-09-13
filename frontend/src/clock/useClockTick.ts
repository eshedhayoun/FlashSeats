import { useSyncExternalStore } from "react";

const listeners = new Set<() => void>();
let tick = 0;
let scheduled = false;

function notify() {
  tick += 1;
  listeners.forEach((listener) => listener());
  scheduled = false;
  schedule();
}

function schedule() {
  if (scheduled || listeners.size === 0) return;

  scheduled = true;
  window.setTimeout(() => {
    window.requestAnimationFrame(notify);
  }, 1000);
}

function subscribe(listener: () => void) {
  listeners.add(listener);
  schedule();

  return () => {
    listeners.delete(listener);
    if (listeners.size === 0) scheduled = false;
  };
}

function getSnapshot() {
  return tick;
}

export function useClockTick() {
  useSyncExternalStore(subscribe, getSnapshot, getSnapshot);
}

const OFFSET_KEY = "fs.clockOffsetMs";

/**
 * The server's clock, as an offset from the device's (FE_SPEC §0 rule 1). Every countdown derives from
 * it, so a device four minutes fast still sees a reservation last as long as the server says.
 *
 * The offset is the one deliberately global session key (FE_SPEC §3): there is a single server
 * clock, and every `serverTime` from any sale refreshes the same value. It is kept so the first
 * paint after a reload already runs on server time.
 */
export class ServerClock {
  private offsetMs: number;

  constructor() {
    this.offsetMs = readStoredOffset();
  }

  update(serverTime: string) {
    const parsed = Date.parse(serverTime);
    if (Number.isNaN(parsed)) return;
    this.offsetMs = parsed - Date.now();
    try {
      sessionStorage.setItem(OFFSET_KEY, String(this.offsetMs));
    } catch {
      // Storage can be unavailable (private mode, quota). The in-memory offset still works.
    }
  }

  now() {
    return Date.now() + this.offsetMs;
  }

  remainingMs(expiresAt: string) {
    return Date.parse(expiresAt) - this.now();
  }
}

function readStoredOffset(): number {
  try {
    const stored = Number(sessionStorage.getItem(OFFSET_KEY));
    return Number.isFinite(stored) ? stored : 0;
  } catch {
    return 0;
  }
}

export const serverClock = new ServerClock();

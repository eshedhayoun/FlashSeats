export class ServerClock {
  private offsetMs = 0;

  update(serverTime: string) {
    this.offsetMs = Date.parse(serverTime) - Date.now();
  }

  now() {
    return Date.now() + this.offsetMs;
  }

  remainingMs(expiresAt: string) {
    return Date.parse(expiresAt) - this.now();
  }
}

export const serverClock = new ServerClock();

import { beforeEach, describe, expect, it } from "vitest";
import {
  clearHoldStorage,
  getHoldToken,
  getIdempotencyKey,
  getSaleValue,
  setHoldToken,
  setSaleValue
} from "./storage";

describe("event-scoped storage", () => {
  beforeEach(() => {
    sessionStorage.clear();
    localStorage.clear();
  });

  it("keeps values for concurrent sales isolated", () => {
    setHoldToken(1, "hold-a");
    setHoldToken(2, "hold-b");

    expect(getHoldToken(1)).toBe("hold-a");
    expect(getHoldToken(2)).toBe("hold-b");
  });

  it("reuses one idempotency key for a hold", () => {
    const first = getIdempotencyKey(1, "hold-a");
    const second = getIdempotencyKey(1, "hold-a");

    expect(second).toBe(first);
    expect(getSaleValue(1, "idem.hold-a")).toBe(first);
  });

  it("does not clear another event when settling a hold", () => {
    setHoldToken(1, "hold-a");
    setHoldToken(2, "hold-b");
    getIdempotencyKey(1, "hold-a");
    getIdempotencyKey(2, "hold-b");

    clearHoldStorage(1, "hold-a");

    expect(getHoldToken(1)).toBeNull();
    expect(getHoldToken(2)).toBe("hold-b");
    expect(getSaleValue(2, "idem.hold-b")).not.toBeNull();
  });
});

import { describe, expect, it } from "vitest";
import type { EventListItem } from "../api/types";
import { sortEvents } from "./EventIndexPage";

const event = (eventId: number, windowStatus: EventListItem["windowStatus"], saleStartTime: string): EventListItem => ({
  eventId,
  title: `E${eventId}`,
  venueName: "V",
  eventStartTime: "2026-12-01T20:00:00Z",
  saleStartTime,
  windowStatus
});

describe("sortEvents", () => {
  it("puts what is on sale first and what has ended last", () => {
    const sorted = sortEvents([
      event(1, "CLOSED", "2026-09-01T00:00:00Z"),
      event(2, "UPCOMING", "2026-11-02T00:00:00Z"),
      event(3, "OPEN", "2026-10-01T00:00:00Z"),
      event(4, "UPCOMING", "2026-11-01T00:00:00Z"),
      event(5, "CLOSED", "2026-09-20T00:00:00Z"),
      event(6, "PAUSED", "2026-10-02T00:00:00Z")
    ]);
    expect(sorted.map((e) => e.eventId)).toEqual([3, 6, 4, 2, 5, 1]);
  });
});

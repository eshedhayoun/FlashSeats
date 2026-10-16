import { describe, expect, it } from "vitest";
import { actionsFor, readiness, type AdminEvent } from "./adminApi";
import { defaultForm, toCents, toCreateRequest, toLocalInput, type EventForm } from "./eventForm";

const NOW = new Date(2026, 9, 16, 12, 0, 0);

function form(overrides: Partial<EventForm> = {}): EventForm {
  return { ...defaultForm(NOW), title: "Midnight Sessions", venueName: "The Roundhouse", ...overrides };
}

function event(overrides: Partial<AdminEvent> = {}): AdminEvent {
  return {
    eventId: 1,
    title: "Midnight Sessions",
    description: null,
    venueName: "The Roundhouse",
    eventStartTime: "2026-10-23T18:00:00Z",
    saleStartTime: "2026-10-16T12:10:00Z",
    saleEndTime: "2026-10-16T16:10:00Z",
    status: "PUBLISHED",
    windowStatus: "UPCOMING",
    prewarmed: true,
    deletable: true,
    tiers: [],
    ...overrides
  };
}

describe("toCents", () => {
  it("reads dollars and cents without float drift", () => {
    expect(toCents("45.10")).toBe(4510);
    expect(toCents("0.5")).toBe(50);
    expect(toCents("120")).toBe(12000);
    expect(toCents(" 19.99 ")).toBe(1999);
  });

  it("refuses anything that is not an amount", () => {
    expect(toCents("")).toBeNull();
    expect(toCents("1.234")).toBeNull();
    expect(toCents("-5")).toBeNull();
    expect(toCents("$5")).toBeNull();
  });
});

describe("toCreateRequest", () => {
  it("accepts the default form once it has a title and venue", () => {
    const result = toCreateRequest(form(), NOW);
    expect("request" in result).toBe(true);
    if (!("request" in result)) return;
    expect(result.request.tiers).toEqual([
      { tierName: "General Admission", priceCents: 5000, totalCapacity: 100, maxPerOrder: 4 }
    ]);
    expect(result.request.description).toBeNull();
  });

  it("sends the operator's local time as the same instant", () => {
    const start = new Date(2026, 9, 16, 14, 30);
    const result = toCreateRequest(form({ saleStartTime: toLocalInput(start) }), NOW);
    expect("request" in result && result.request.saleStartTime).toBe(start.toISOString());
  });

  it.each([
    ["no title", { title: " " }, "title"],
    ["a sale starting too soon", { saleStartTime: toLocalInput(new Date(NOW.getTime() + 60_000)) }, "2 minutes"],
    ["a sale ending before it starts", { saleEndTime: toLocalInput(NOW) }, "end after"],
    ["an event before its sale", { eventStartTime: toLocalInput(new Date(NOW.getTime() + 5 * 60_000)) }, "event must start"],
    ["no tiers", { tiers: [] }, "at least one tier"],
    ["a price under 50 cents", { tiers: [{ tierName: "GA", price: "0.49", totalCapacity: "10", maxPerOrder: "4" }] }, "$0.50"],
    ["more per order than a hold allows", { tiers: [{ tierName: "GA", price: "10", totalCapacity: "10", maxPerOrder: "7" }] }, "between 1 and 6"],
    ["a capacity of zero", { tiers: [{ tierName: "GA", price: "10", totalCapacity: "0", maxPerOrder: "4" }] }, "capacity"]
  ])("refuses %s", (_, overrides, message) => {
    const result = toCreateRequest(form(overrides as Partial<EventForm>), NOW);
    expect("error" in result && result.error.toLowerCase()).toContain(message.toLowerCase());
  });
});

describe("actionsFor", () => {
  it("offers pre-warm only to an upcoming sale that needs it", () => {
    expect(actionsFor(event({ prewarmed: false }))).toContain("prewarm");
    expect(actionsFor(event({ prewarmed: true }))).not.toContain("prewarm");
    expect(actionsFor(event({ windowStatus: "OPEN", prewarmed: false, deletable: false }))).not.toContain("prewarm");
  });

  it("pauses a published sale and resumes a paused one, never a closed one", () => {
    expect(actionsFor(event({ windowStatus: "OPEN", deletable: false }))).toEqual(["pause", "rebuild"]);
    expect(actionsFor(event({ status: "PAUSED", windowStatus: "PAUSED", deletable: false }))).toEqual(["resume", "rebuild"]);
    expect(actionsFor(event({ windowStatus: "CLOSED", deletable: false }))).toEqual([]);
  });

  it("offers delete exactly when the server says it would accept it", () => {
    expect(actionsFor(event())).toEqual(["pause", "delete"]);
    expect(actionsFor(event({ deletable: false }))).toEqual(["pause"]);
  });

  it("does nothing to a cancelled sale", () => {
    expect(actionsFor(event({ status: "CANCELLED", windowStatus: "CLOSED", deletable: false }))).toEqual([]);
  });
});

describe("readiness", () => {
  it("is silent when the sale will sell", () => {
    expect(readiness(event())).toBeNull();
  });

  it("asks for a pre-warm before the sale and a rebuild during it", () => {
    expect(readiness(event({ prewarmed: false }))?.severity).toBe("warning");
    expect(readiness(event({ prewarmed: false, windowStatus: "OPEN" }))?.message).toContain("rebuild");
  });
});

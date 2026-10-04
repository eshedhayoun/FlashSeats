import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { AvailabilityChip } from "./StatusChip";

/** ADR-040: an unreadable counter is a fault, and nothing this client cannot name is "sold out". */
describe("AvailabilityChip", () => {
  it("shows an unreadable counter as checking, never sold out", () => {
    render(<AvailabilityChip level="UNKNOWN" />);
    expect(screen.getByText("Checking…")).toBeTruthy();
  });

  it("falls through to checking for a value it does not know", () => {
    render(<AvailabilityChip level="SOMETHING_NEW" />);
    expect(screen.getByText("Checking…")).toBeTruthy();
    expect(screen.queryByText("Sold out")).toBeNull();
  });

  it("names the buckets it knows", () => {
    render(<AvailabilityChip level="SOLD_OUT" />);
    expect(screen.getByText("Sold out")).toBeTruthy();
  });
});

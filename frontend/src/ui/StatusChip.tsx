import Chip from "@mui/material/Chip";
import { alpha, useTheme } from "@mui/material/styles";
import type { Availability, WindowStatus } from "../api/types";

type Tone = "success" | "warning" | "info" | "neutral" | "error";

function useToneStyle(tone: Tone) {
  const theme = useTheme();
  const dark = theme.palette.mode === "dark";
  const color =
    tone === "neutral" ? theme.palette.text.secondary : theme.palette[tone].main;
  // Tinted background, darkened text in light mode: every chip clears 6:1.
  return {
    color: dark || tone === "neutral" ? color : theme.palette[tone].dark,
    backgroundColor: alpha(color, dark ? 0.16 : 0.12),
    border: `1px solid ${alpha(color, 0.35)}`,
    minWidth: 88
  };
}

const AVAILABILITY: Record<Availability, { label: string; tone: Tone }> = {
  PLENTY: { label: "Available", tone: "success" },
  LIMITED: { label: "Limited", tone: "warning" },
  SOLD_OUT: { label: "Sold out", tone: "neutral" },
  // A fault, never a bucket: neutral, never the sold-out treatment (ADR-040).
  UNKNOWN: { label: "Checking…", tone: "info" }
};

/**
 * A tier's availability, as a bucket and never a number (ADR-027). Any value this client does not
 * recognise falls through to "Checking…", never to "Sold out" (FE_SPEC V1).
 */
export function AvailabilityChip({ level }: { level: Availability | string }) {
  const known = AVAILABILITY[level as Availability] ?? AVAILABILITY.UNKNOWN;
  const style = useToneStyle(known.tone);
  return <Chip size="small" label={known.label} sx={style} />;
}

const WINDOW: Record<WindowStatus, { label: string; tone: Tone }> = {
  UPCOMING: { label: "Opens soon", tone: "info" },
  OPEN: { label: "On sale now", tone: "success" },
  PAUSED: { label: "Paused", tone: "warning" },
  CLOSED: { label: "Sale ended", tone: "neutral" }
};

export function WindowChip({ status }: { status: WindowStatus }) {
  const known = WINDOW[status] ?? WINDOW.UPCOMING;
  const style = useToneStyle(known.tone);
  return <Chip size="small" label={known.label} sx={style} />;
}

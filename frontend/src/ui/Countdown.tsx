import Box from "@mui/material/Box";
import { keyframes, useTheme } from "@mui/material/styles";
import { memo, useEffect, useRef, useState } from "react";
import { serverClock } from "../clock/serverClock";
import { useClockTick } from "../clock/useClockTick";
import { describeDuration, formatDuration } from "../format/duration";
import { visuallyHidden } from "./VisuallyHidden";

/**
 * - `hold` — the checkout reservation: the anxiety surface (FE_SPEC V4).
 * - `admission` — the turn to choose seats: low-anxiety, amber only in the last minute (FE_SPEC V3).
 * - `presale` — time until the sale opens: never urgent.
 */
export type CountdownVariant = "hold" | "admission" | "presale";
export type CountdownTone = "neutral" | "warning" | "critical";

export function countdownTone(variant: CountdownVariant, remainingMs: number): CountdownTone {
  if (variant === "hold") {
    if (remainingMs < 60_000) return "critical";
    if (remainingMs <= 120_000) return "warning";
    return "neutral";
  }
  if (variant === "admission") return remainingMs < 60_000 ? "warning" : "neutral";
  return "neutral";
}

/** The threshold crossings worth saying out loud, and nothing in between (FE_SPEC §6). */
const ANNOUNCEMENTS: Record<CountdownVariant, Array<{ belowMs: number; text: string; urgent: boolean }>> = {
  hold: [
    { belowMs: 120_000, text: "Two minutes left to complete your purchase.", urgent: false },
    { belowMs: 60_000, text: "Less than a minute left to complete your purchase.", urgent: true }
  ],
  admission: [{ belowMs: 60_000, text: "One minute left to choose your seats.", urgent: false }],
  presale: [{ belowMs: 1, text: "The sale is open.", urgent: false }]
};

const pulse = keyframes`
  0%, 100% { opacity: 1; }
  50% { opacity: 0.6; }
`;

type CountdownProps = {
  expiresAt: string;
  variant: CountdownVariant;
  /** Called once when the remainder reaches zero. A prompt to ask the server, never a conclusion. */
  onExpire?: () => void;
};

/**
 * Time left until `expiresAt`, on the server's clock (FE_SPEC §0 rule 1). Memoised and driven by the
 * one app-wide tick, so it re-renders itself every second and nothing around it.
 */
export const Countdown = memo(function Countdown({ expiresAt, variant, onExpire }: CountdownProps) {
  useClockTick();
  const theme = useTheme();
  const remainingMs = serverClock.remainingMs(expiresAt);
  const tone = countdownTone(variant, remainingMs);

  const expired = useRef<string | null>(null);
  useEffect(() => {
    if (remainingMs > 0) {
      expired.current = null;
      return;
    }
    if (expired.current === expiresAt) return;
    expired.current = expiresAt;
    onExpire?.();
  }, [remainingMs, expiresAt, onExpire]);

  const color =
    tone === "critical" ? theme.palette.error.main : tone === "warning" ? theme.palette.warning.main : "inherit";
  // A gentle pulse under a minute, and none in the last ten seconds: never flash, never shake.
  const pulsing = tone === "critical" && remainingMs >= 10_000;

  return (
    <>
      <Box
        component="span"
        className="tabular"
        role="timer"
        aria-label={describeDuration(remainingMs)}
        data-tone={tone}
        sx={{
          display: "inline-block",
          fontWeight: 700,
          color,
          animation: pulsing ? `${pulse} 1s ease-in-out infinite` : "none"
        }}
      >
        {formatDuration(remainingMs)}
      </Box>
      <ThresholdAnnouncer variant={variant} remainingMs={remainingMs} expiresAt={expiresAt} />
    </>
  );
});

function ThresholdAnnouncer({
  variant,
  remainingMs,
  expiresAt
}: {
  variant: CountdownVariant;
  remainingMs: number;
  expiresAt: string;
}) {
  const [message, setMessage] = useState<{ text: string; urgent: boolean } | null>(null);
  const announced = useRef<{ expiresAt: string; belowMs: number } | null>(null);

  useEffect(() => {
    const crossed = ANNOUNCEMENTS[variant]
      .filter((threshold) => remainingMs < threshold.belowMs)
      .at(-1);
    if (!crossed) return;
    const last = announced.current;
    if (last && last.expiresAt === expiresAt && last.belowMs <= crossed.belowMs) return;
    announced.current = { expiresAt, belowMs: crossed.belowMs };
    setMessage({ text: crossed.text, urgent: crossed.urgent });
  }, [variant, remainingMs, expiresAt]);

  return (
    <Box component="span" sx={visuallyHidden} aria-live={message?.urgent ? "assertive" : "polite"}>
      {message?.text ?? ""}
    </Box>
  );
}

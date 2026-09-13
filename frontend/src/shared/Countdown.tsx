import { serverClock } from "../clock/serverClock";
import { useClockTick } from "../clock/useClockTick";
import { describeDuration, formatDuration } from "./formatDuration";

export type CountdownTone = "neutral" | "warning" | "critical";

type CountdownProps = {
  expiresAt: string;
  className?: string;
  announce?: boolean;
};

export function Countdown({
  expiresAt,
  className,
  announce = true
}: CountdownProps) {
  useClockTick();

  const remainingMs = serverClock.remainingMs(expiresAt);
  const tone: CountdownTone =
    remainingMs <= 60_000
      ? "critical"
      : remainingMs <= 120_000
        ? "warning"
        : "neutral";

  return (
    <span
      className={className}
      data-tone={tone}
      role="timer"
      aria-live={announce ? "polite" : "off"}
      aria-label={describeDuration(remainingMs)}
      style={{
        fontVariantNumeric: "tabular-nums",
        minWidth: "5ch",
        display: "inline-block"
      }}
    >
      {formatDuration(remainingMs)}
    </span>
  );
}

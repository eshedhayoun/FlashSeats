import { serverClock } from "../clock/serverClock";
import { useClockTick } from "../clock/useClockTick";
import { describeDuration, formatDuration } from "./formatDuration";
import { useTheme } from "@mui/material/styles";

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
  const theme = useTheme();

  const remainingMs = serverClock.remainingMs(expiresAt);
  const tone: CountdownTone =
    remainingMs <= 60_000
      ? "critical"
      : remainingMs <= 120_000
        ? "warning"
        : "neutral";

  const toneColor =
    tone === "critical"
      ? theme.palette.error.main
      : tone === "warning"
        ? theme.palette.warning.main
        : "inherit";

  // Apply pulse animation for critical tone
  const keyframes = `
    @keyframes pulse {
      0%, 100% { opacity: 1; }
      50% { opacity: 0.7; }
    }
  `;

  const style = {
    fontVariantNumeric: "tabular-nums" as const,
    minWidth: "5ch",
    display: "inline-block" as const,
    color: toneColor,
    ...(tone === "critical" && {
      animation: "pulse 1s ease-in-out infinite",
      "@media (prefers-reduced-motion: reduce)": {
        animation: "none"
      }
    })
  };

  return (
    <>
      <style>{keyframes}</style>
      <span
        className={className}
        data-tone={tone}
        role="timer"
        aria-live={announce ? "polite" : "off"}
        aria-label={describeDuration(remainingMs)}
        style={style}
      >
        {formatDuration(remainingMs)}
      </span>
    </>
  );
}

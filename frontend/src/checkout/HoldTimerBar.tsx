import Box from "@mui/material/Box";
import Stack from "@mui/material/Stack";
import Typography from "@mui/material/Typography";
import { alpha, useTheme } from "@mui/material/styles";
import TimerRounded from "@mui/icons-material/TimerRounded";
import { memo } from "react";
import { serverClock } from "../clock/serverClock";
import { useClockTick } from "../clock/useClockTick";
import { Countdown, countdownTone } from "../ui/Countdown";

/**
 * The hold, always in view (FE_SPEC V4): sticky, and urgent in words as well as colour. It re-renders
 * itself every second and nothing else — the payment form below must never remount mid-entry.
 */
export const HoldTimerBar = memo(function HoldTimerBar({
  expiresAt,
  paying,
  onExpire
}: {
  expiresAt: string;
  paying: boolean;
  onExpire: () => void;
}) {
  useClockTick();
  const theme = useTheme();
  const remainingMs = serverClock.remainingMs(expiresAt);
  const tone = countdownTone("hold", remainingMs);
  const accent =
    tone === "critical" ? theme.palette.error.main : tone === "warning" ? theme.palette.warning.main : theme.palette.primary.main;

  const hint =
    remainingMs <= 0
      ? paying
        ? "Completing your purchase…"
        : "Checking your reservation…"
      : tone === "critical"
        ? "Less than a minute remaining"
        : tone === "warning"
          ? "Complete your purchase soon"
          : "Take your time — they're yours until then";

  return (
    <Box
      data-testid="hold-timer"
      data-tone={tone}
      sx={{
        position: "sticky",
        top: { xs: 68, sm: 72 },
        zIndex: 3,
        px: 2,
        py: 1.5,
        borderRadius: 3,
        border: 1,
        borderColor: alpha(accent, 0.5),
        bgcolor: theme.palette.background.paper,
        boxShadow: `0 6px 18px ${alpha(theme.palette.common.black, theme.palette.mode === "dark" ? 0.4 : 0.08)}`
      }}
    >
      <Stack direction="row" spacing={1.5} alignItems="center">
        <TimerRounded sx={{ color: accent }} aria-hidden />
        <Box>
          <Typography fontWeight={700}>
            Your seats are held for <Countdown expiresAt={expiresAt} variant="hold" onExpire={onExpire} />
          </Typography>
          <Typography variant="body2" color="text.secondary">
            {hint}
          </Typography>
        </Box>
      </Stack>
    </Box>
  );
});

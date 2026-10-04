import AppBar from "@mui/material/AppBar";
import Box from "@mui/material/Box";
import Button from "@mui/material/Button";
import Container from "@mui/material/Container";
import IconButton from "@mui/material/IconButton";
import Link from "@mui/material/Link";
import Toolbar from "@mui/material/Toolbar";
import Tooltip from "@mui/material/Tooltip";
import Typography from "@mui/material/Typography";
import { alpha } from "@mui/material/styles";
import ConfirmationNumberRounded from "@mui/icons-material/ConfirmationNumberRounded";
import DarkModeRounded from "@mui/icons-material/DarkModeRounded";
import LightModeRounded from "@mui/icons-material/LightModeRounded";
import SettingsBrightnessRounded from "@mui/icons-material/SettingsBrightnessRounded";
import WifiOffRounded from "@mui/icons-material/WifiOffRounded";
import type { ReactNode } from "react";
import { Link as RouterLink } from "react-router-dom";
import { stripeEnabled } from "../stripe";
import { BrandMark } from "../ui/BrandMark";
import { useColorMode } from "./colorMode";
import { useOnline } from "./useOnline";

const THEME_LABEL = { system: "system setting", light: "light", dark: "dark" } as const;

export function AppShell({ children }: { children: ReactNode }) {
  const { preference, cyclePreference } = useColorMode();
  const online = useOnline();
  const ThemeIcon =
    preference === "system" ? SettingsBrightnessRounded : preference === "light" ? LightModeRounded : DarkModeRounded;

  return (
    <Box sx={{ minHeight: "100vh", display: "flex", flexDirection: "column", bgcolor: "background.default" }}>
      <Box
        component="a"
        href="#main"
        sx={{
          position: "absolute",
          left: 8,
          top: -64,
          zIndex: 2000,
          px: 2,
          py: 1,
          borderRadius: 2,
          bgcolor: "primary.main",
          color: "primary.contrastText",
          fontWeight: 700,
          "&:focus": { top: 8 }
        }}
      >
        Skip to content
      </Box>

      <AppBar
        position="sticky"
        color="inherit"
        elevation={0}
        sx={(theme) => ({
          borderBottom: 1,
          borderColor: "divider",
          bgcolor: alpha(theme.palette.background.paper, 0.86),
          backdropFilter: "saturate(180%) blur(10px)"
        })}
      >
        <Container maxWidth="lg">
          <Toolbar disableGutters sx={{ gap: 1, minHeight: { xs: 60, sm: 64 } }}>
            <Link component={RouterLink} to="/" color="inherit" underline="none" aria-label="FlashSeats, all events">
              <BrandMark />
            </Link>
            <Box sx={{ flex: 1 }} />
            <Button component={RouterLink} to="/#tickets" color="inherit" startIcon={<ConfirmationNumberRounded />}>
              My tickets
            </Button>
            <Tooltip title={`Theme: ${THEME_LABEL[preference]}`}>
              <IconButton
                onClick={cyclePreference}
                aria-label={`Colour theme: ${THEME_LABEL[preference]}. Change it`}
                color="inherit"
              >
                <ThemeIcon />
              </IconButton>
            </Tooltip>
          </Toolbar>
        </Container>
      </AppBar>

      {!online && (
        <Box role="status" sx={{ bgcolor: "warning.main", color: "common.black", py: 1 }}>
          <Container maxWidth="lg" sx={{ display: "flex", gap: 1, alignItems: "center" }}>
            <WifiOffRounded fontSize="small" aria-hidden />
            <Typography variant="body2" fontWeight={650}>
              You're offline. We'll pick up where you left off when you're back — your place and your
              seats are kept on our side.
            </Typography>
          </Container>
        </Box>
      )}

      <Box component="main" id="main" tabIndex={-1} sx={{ flex: 1, outline: "none" }}>
        {children}
      </Box>

      <Box component="footer" sx={{ borderTop: 1, borderColor: "divider", py: 3, mt: 6 }}>
        <Container maxWidth="lg" sx={{ display: "flex", flexWrap: "wrap", gap: 1, justifyContent: "space-between" }}>
          <Typography variant="body2" color="text.secondary">
            FlashSeats — fair, fast ticket sales.
          </Typography>
          <Typography variant="body2" color="text.secondary" data-testid="payment-mode">
            {stripeEnabled
              ? "Card payments by Stripe, in test mode."
              : "Demo payments: no card is ever charged."}
          </Typography>
        </Container>
      </Box>
    </Box>
  );
}

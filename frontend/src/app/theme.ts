import { alpha, createTheme, responsiveFontSizes, type Theme } from "@mui/material/styles";

export type ColorMode = "light" | "dark";

/**
 * The design tokens (Step 9a, FE_SPEC §10). Every text/background pair is WCAG AA: body text ≥ 4.5:1
 * on every surface it sits on, the chips ≥ 6:1, white on every stop of the hero gradient ≥ 6:1.
 */
const tokens = {
  light: {
    background: "#F6F7FB",
    paper: "#FFFFFF",
    subtle: "#EEF0F7",
    text: "#151826",
    textSecondary: "#4B5165",
    divider: "#DDE1EC",
    primary: "#4338CA",
    primaryContrast: "#FFFFFF",
    success: "#15803D",
    warning: "#B45309",
    error: "#B91C1C",
    info: "#0369A1"
  },
  dark: {
    background: "#0D0F1A",
    paper: "#161927",
    subtle: "#1E2234",
    text: "#ECEEF8",
    textSecondary: "#A6ACC4",
    divider: "#2B3048",
    primary: "#A5B4FC",
    primaryContrast: "#111427",
    success: "#4ADE80",
    warning: "#FBBF24",
    error: "#F87171",
    info: "#38BDF8"
  }
} as const;

/** The brand band behind an event's title. White text holds ≥ 6:1 at every stop. */
export const HERO_GRADIENT = "linear-gradient(135deg, #4338CA 0%, #6D28D9 55%, #BE185D 100%)";

declare module "@mui/material/styles" {
  interface Palette {
    subtle: string;
  }
  interface PaletteOptions {
    subtle?: string;
  }
}

export function createAppTheme(mode: ColorMode): Theme {
  const t = tokens[mode];

  const theme = createTheme({
    palette: {
      mode,
      primary: { main: t.primary, contrastText: t.primaryContrast },
      success: { main: t.success },
      warning: { main: t.warning },
      error: { main: t.error },
      info: { main: t.info },
      background: { default: t.background, paper: t.paper },
      text: { primary: t.text, secondary: t.textSecondary },
      divider: t.divider,
      subtle: t.subtle
    },
    shape: { borderRadius: 12 },
    typography: {
      // The system stack: nothing to download, and the cluster may have no internet.
      fontFamily:
        "system-ui, -apple-system, 'Segoe UI', Roboto, 'Helvetica Neue', Arial, sans-serif",
      h1: { fontSize: "3rem", fontWeight: 800, letterSpacing: "-0.03em", lineHeight: 1.1 },
      h2: { fontSize: "2rem", fontWeight: 750, letterSpacing: "-0.02em", lineHeight: 1.2 },
      h3: { fontSize: "1.5rem", fontWeight: 700, letterSpacing: "-0.01em", lineHeight: 1.25 },
      h4: { fontSize: "1.25rem", fontWeight: 700, lineHeight: 1.3 },
      h5: { fontSize: "1.125rem", fontWeight: 650, lineHeight: 1.35 },
      h6: { fontSize: "1rem", fontWeight: 650, lineHeight: 1.4 },
      body1: { lineHeight: 1.55 },
      body2: { lineHeight: 1.5 },
      button: { textTransform: "none", fontWeight: 650, letterSpacing: 0 },
      overline: { fontWeight: 700, letterSpacing: "0.08em", lineHeight: 1.6 }
    },
    components: {
      MuiCssBaseline: {
        styleOverrides: {
          body: { WebkitFontSmoothing: "antialiased" },
          ".tabular": { fontVariantNumeric: "tabular-nums" },
          // Reduced motion turns every animation and transition off, not just the decorative ones.
          "@media (prefers-reduced-motion: reduce)": {
            "*, *::before, *::after": {
              animationDuration: "0.01ms !important",
              animationIterationCount: "1 !important",
              transitionDuration: "0.01ms !important",
              scrollBehavior: "auto !important"
            }
          }
        }
      },
      MuiButton: {
        defaultProps: { disableElevation: true },
        styleOverrides: {
          root: { borderRadius: 10 },
          sizeLarge: { minHeight: 48, paddingInline: 24, fontSize: "1rem" }
        }
      },
      MuiCard: {
        defaultProps: { variant: "outlined" },
        styleOverrides: { root: { borderRadius: 16, backgroundImage: "none" } }
      },
      MuiPaper: { styleOverrides: { root: { backgroundImage: "none" } } },
      MuiAlert: {
        styleOverrides: {
          root: { borderRadius: 12, alignItems: "flex-start" },
          message: { width: "100%" }
        }
      },
      MuiChip: { styleOverrides: { root: { fontWeight: 650 } } },
      MuiTextField: { defaultProps: { fullWidth: true } },
      MuiLink: { defaultProps: { underline: "hover" } },
      MuiLinearProgress: {
        styleOverrides: {
          root: { height: 10, borderRadius: 999, backgroundColor: alpha(t.primary, 0.15) },
          bar: { borderRadius: 999 }
        }
      },
      MuiTooltip: { defaultProps: { arrow: true } }
    }
  });

  return responsiveFontSizes(theme, { factor: 2.2 });
}

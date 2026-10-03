import CssBaseline from "@mui/material/CssBaseline";
import { ThemeProvider } from "@mui/material/styles";
import { useMemo } from "react";
import { BrowserRouter } from "react-router-dom";
import { AppShell } from "./AppShell";
import { ColorModeProvider } from "./colorMode";
import { ErrorBoundary } from "./ErrorBoundary";
import { AppRoutes } from "./routes";
import { createAppTheme, type ColorMode } from "./theme";

function Themed({ mode }: { mode: ColorMode }) {
  const theme = useMemo(() => createAppTheme(mode), [mode]);
  return (
    <ThemeProvider theme={theme}>
      <CssBaseline enableColorScheme />
      <BrowserRouter>
        <AppShell>
          <ErrorBoundary>
            <AppRoutes />
          </ErrorBoundary>
        </AppShell>
      </BrowserRouter>
    </ThemeProvider>
  );
}

export function App() {
  return <ColorModeProvider>{(mode) => <Themed mode={mode} />}</ColorModeProvider>;
}

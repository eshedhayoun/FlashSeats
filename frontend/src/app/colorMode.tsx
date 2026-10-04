import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from "react";
import useMediaQuery from "@mui/material/useMediaQuery";
import type { ColorMode } from "./theme";

export type ColorPreference = ColorMode | "system";

const PREFERENCE_KEY = "fs.theme";

type ColorModeContextValue = {
  preference: ColorPreference;
  mode: ColorMode;
  cyclePreference: () => void;
};

const ColorModeContext = createContext<ColorModeContextValue | null>(null);

/**
 * Light, dark, or whatever the system says. The one global UI preference, kept in `localStorage`
 * because it is about the person, not about a sale or a tab.
 */
export function ColorModeProvider({ children }: { children: (mode: ColorMode) => ReactNode }) {
  const prefersDark = useMediaQuery("(prefers-color-scheme: dark)", { noSsr: true });
  const [preference, setPreference] = useState<ColorPreference>(readPreference);

  useEffect(() => {
    try {
      localStorage.setItem(PREFERENCE_KEY, preference);
    } catch {
      // A preference that cannot be stored still applies for this visit.
    }
  }, [preference]);

  const mode: ColorMode = preference === "system" ? (prefersDark ? "dark" : "light") : preference;

  const cyclePreference = useCallback(() => {
    setPreference((current) => (current === "system" ? "light" : current === "light" ? "dark" : "system"));
  }, []);

  const value = useMemo(() => ({ preference, mode, cyclePreference }), [preference, mode, cyclePreference]);

  return <ColorModeContext.Provider value={value}>{children(mode)}</ColorModeContext.Provider>;
}

export function useColorMode(): ColorModeContextValue {
  const value = useContext(ColorModeContext);
  if (!value) throw new Error("useColorMode outside ColorModeProvider");
  return value;
}

function readPreference(): ColorPreference {
  try {
    const stored = localStorage.getItem(PREFERENCE_KEY);
    return stored === "light" || stored === "dark" || stored === "system" ? stored : "system";
  } catch {
    return "system";
  }
}

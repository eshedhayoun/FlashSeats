import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from "react";
import type { ColorMode } from "./theme";

const PREFERENCE_KEY = "fs.theme";

type ColorModeContextValue = {
  mode: ColorMode;
  toggleMode: () => void;
};

const ColorModeContext = createContext<ColorModeContextValue | null>(null);

/**
 * Light or dark. The system setting picks the first one; after that the buyer's choice wins. The one
 * global UI preference, kept in `localStorage` because it is about the person, not a sale or a tab.
 */
export function ColorModeProvider({ children }: { children: (mode: ColorMode) => ReactNode }) {
  const [mode, setMode] = useState<ColorMode>(readMode);

  useEffect(() => {
    try {
      localStorage.setItem(PREFERENCE_KEY, mode);
    } catch {
      // A preference that cannot be stored still applies for this visit.
    }
  }, [mode]);

  const toggleMode = useCallback(() => {
    setMode((current) => (current === "light" ? "dark" : "light"));
  }, []);

  const value = useMemo(() => ({ mode, toggleMode }), [mode, toggleMode]);

  return <ColorModeContext.Provider value={value}>{children(mode)}</ColorModeContext.Provider>;
}

export function useColorMode(): ColorModeContextValue {
  const value = useContext(ColorModeContext);
  if (!value) throw new Error("useColorMode outside ColorModeProvider");
  return value;
}

function readMode(): ColorMode {
  try {
    const stored = localStorage.getItem(PREFERENCE_KEY);
    if (stored === "light" || stored === "dark") return stored;
  } catch {
    // Fall through to the system setting.
  }
  // Nothing chosen yet, or the old "system" value from before the toggle had two states.
  return typeof window !== "undefined" && window.matchMedia?.("(prefers-color-scheme: dark)").matches ? "dark" : "light";
}

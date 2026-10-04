import Box from "@mui/material/Box";
import type { ReactNode } from "react";

/** Present for assistive technology, absent on screen. */
export const visuallyHidden = {
  // Pixel strings, not numbers: in `sx`, a number of 1 or less is read as a fraction — `width: 1` is 100%.
  position: "absolute",
  width: "1px",
  height: "1px",
  padding: 0,
  margin: "-1px",
  overflow: "hidden",
  clip: "rect(0 0 0 0)",
  whiteSpace: "nowrap",
  border: 0
} as const;

export function VisuallyHidden({ children }: { children: ReactNode }) {
  return (
    <Box component="span" sx={visuallyHidden}>
      {children}
    </Box>
  );
}

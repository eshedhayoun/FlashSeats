import Typography, { type TypographyProps } from "@mui/material/Typography";
import { useEffect, useRef, type ReactNode } from "react";

/**
 * A view's one `h1`. It takes focus when the view appears, so a screen reader announces every view
 * change — the buyer moves between views without a page load, and without this nothing would say
 * that anything happened.
 */
export function PageTitle({
  children,
  variant = "h2",
  sx,
  autoFocus = true,
  dir
}: {
  children: ReactNode;
  variant?: TypographyProps["variant"];
  sx?: TypographyProps["sx"];
  autoFocus?: boolean;
  /** `auto` for operator-supplied text, so a Hebrew or Arabic title reads in its own direction. */
  dir?: "auto";
}) {
  const ref = useRef<HTMLHeadingElement>(null);

  useEffect(() => {
    if (autoFocus) ref.current?.focus({ preventScroll: true });
  }, [autoFocus]);

  return (
    <Typography
      ref={ref}
      component="h1"
      variant={variant}
      tabIndex={-1}
      dir={dir}
      sx={{ outline: "none", ...((sx as object) ?? {}) }}
    >
      {children}
    </Typography>
  );
}

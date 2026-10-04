import Alert, { type AlertColor } from "@mui/material/Alert";
import AlertTitle from "@mui/material/AlertTitle";
import Stack from "@mui/material/Stack";
import type { ReactNode } from "react";

/**
 * The one banner. Errors interrupt (`role="alert"`); everything else is announced politely, so a
 * screen reader is not shouted at about a pause or a reconnect.
 */
export function Notice({
  severity,
  title,
  children,
  actions,
  onClose,
  testId
}: {
  severity: AlertColor;
  title?: ReactNode;
  children?: ReactNode;
  actions?: ReactNode;
  onClose?: () => void;
  testId?: string;
}) {
  return (
    <Alert
      severity={severity}
      variant="outlined"
      role={severity === "error" ? "alert" : "status"}
      onClose={onClose}
      data-testid={testId}
      sx={{ bgcolor: "background.paper" }}
    >
      {title && <AlertTitle sx={{ fontWeight: 700 }}>{title}</AlertTitle>}
      {children}
      {actions && (
        <Stack direction="row" spacing={1} sx={{ mt: 1.5, flexWrap: "wrap", rowGap: 1 }}>
          {actions}
        </Stack>
      )}
    </Alert>
  );
}

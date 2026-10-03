import Box from "@mui/material/Box";
import Stack from "@mui/material/Stack";
import Typography from "@mui/material/Typography";
import type { ReactNode } from "react";

export function EmptyState({
  icon,
  title,
  children,
  action
}: {
  icon: ReactNode;
  title: string;
  children?: ReactNode;
  action?: ReactNode;
}) {
  return (
    <Stack
      spacing={1.5}
      alignItems="center"
      textAlign="center"
      sx={{ py: 6, px: 3, border: 1, borderColor: "divider", borderRadius: 4, borderStyle: "dashed" }}
    >
      <Box sx={{ color: "text.secondary", display: "flex" }} aria-hidden>
        {icon}
      </Box>
      <Typography variant="h5" component="h2">
        {title}
      </Typography>
      {children && <Typography color="text.secondary">{children}</Typography>}
      {action}
    </Stack>
  );
}

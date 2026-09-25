import Alert from "@mui/material/Alert";

export function ProblemAlert({ message }: { message: string }) {
  return <Alert severity="error">{message}</Alert>;
}

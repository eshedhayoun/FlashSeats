import Button from "@mui/material/Button";
import Container from "@mui/material/Container";
import Stack from "@mui/material/Stack";
import Typography from "@mui/material/Typography";

export function DegradedState({
  onRetry
}: {
  onRetry: () => void;
}) {
  return (
    <Container maxWidth="sm" sx={{ py: 8 }}>
      <Stack spacing={2}>
        <Typography component="h1" variant="h5">
          We&apos;re checking the sale status
        </Typography>
        <Typography color="text.secondary">
          Some sale information is temporarily unavailable. Retrying is safe;
          we will not assume that your reservation or queue position is gone.
        </Typography>
        <Button variant="outlined" onClick={onRetry}>
          Try again
        </Button>
      </Stack>
    </Container>
  );
}

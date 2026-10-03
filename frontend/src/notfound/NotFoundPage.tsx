import Button from "@mui/material/Button";
import Container from "@mui/material/Container";
import Stack from "@mui/material/Stack";
import Typography from "@mui/material/Typography";
import SearchOffRounded from "@mui/icons-material/SearchOffRounded";
import { Link as RouterLink } from "react-router-dom";
import { PageTitle } from "../ui/PageTitle";

export function NotFoundPage({ what = "page" }: { what?: "page" | "event" }) {
  return (
    <Container maxWidth="sm" sx={{ py: { xs: 6, sm: 10 } }}>
      <Stack spacing={2} alignItems="flex-start">
        <SearchOffRounded sx={{ fontSize: 48, color: "text.secondary" }} aria-hidden />
        <PageTitle variant="h3">{what === "event" ? "We couldn't find that event" : "We couldn't find that page"}</PageTitle>
        <Typography color="text.secondary">
          The link may be mistyped or out of date. The sales that are on now are one click away.
        </Typography>
        <Button component={RouterLink} to="/" variant="contained">
          See all events
        </Button>
      </Stack>
    </Container>
  );
}

import Skeleton from "@mui/material/Skeleton";
import Stack from "@mui/material/Stack";
import { VisuallyHidden } from "./VisuallyHidden";

/**
 * A skeleton in the shape of what is coming, so nothing jumps when it arrives. Shown only before
 * the first answer; refreshes after that happen behind the content (FE_SPEC §3).
 */
export function LoadingState({ label, shape = "panel" }: { label: string; shape?: "panel" | "list" | "event" }) {
  return (
    <Stack spacing={2} sx={{ py: 4 }} role="status" aria-busy="true">
      <VisuallyHidden>{label}</VisuallyHidden>
      {shape === "event" && <Skeleton variant="rounded" height={180} />}
      {shape === "list" ? (
        [0, 1, 2].map((row) => <Skeleton key={row} variant="rounded" height={112} />)
      ) : (
        <>
          <Skeleton variant="text" width="60%" height={48} />
          <Skeleton variant="text" width="40%" />
          <Skeleton variant="rounded" height={72} />
          <Skeleton variant="rounded" height={72} />
          <Skeleton variant="rounded" width={200} height={48} />
        </>
      )}
    </Stack>
  );
}

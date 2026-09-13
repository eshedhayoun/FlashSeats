import { useState } from "react";
import { useParams } from "react-router-dom";
import Button from "@mui/material/Button";
import CircularProgress from "@mui/material/CircularProgress";
import Container from "@mui/material/Container";
import Stack from "@mui/material/Stack";
import Typography from "@mui/material/Typography";
import { ApiError } from "../api/errors";
import { joinQueue } from "../api/endpoints";
import { ProblemAlert } from "../shared/ProblemAlert";
import { EventHeader } from "./EventHeader";
import { TierCard } from "./TierCard";
import { useEvent } from "./useEvent";

export function LandingPage({
  onJoined
}: {
  onJoined: () => Promise<void>;
}) {
  const eventId = Number(useParams().eventId);
  const eventResult = useEvent(eventId);
  const [joining, setJoining] = useState(false);
  const [joinError, setJoinError] = useState<ApiError | null>(null);
  const [selectedTierId, setSelectedTierId] = useState<number | null>(null);

  if (!Number.isInteger(eventId) || eventId <= 0) {
    return <ProblemAlert message="This event link is invalid." />;
  }

  if (eventResult.status === "loading") {
    return (
      <Container sx={{ py: 8 }}>
        <CircularProgress aria-label="Loading event" />
      </Container>
    );
  }

  if (eventResult.error) {
    return (
      <Container sx={{ py: 8 }}>
        <ProblemAlert message={eventResult.error.message} />
      </Container>
    );
  }

  const event = eventResult.event;
  if (!event) {
    return <ProblemAlert message="The event could not be loaded." />;
  }

  const canJoin = event.windowStatus === "OPEN";

  const handleJoin = async () => {
    setJoining(true);
    setJoinError(null);
    try {
      await joinQueue({ eventId });
      await onJoined();
    } catch (cause) {
      setJoinError(
        cause instanceof ApiError
          ? cause
          : new ApiError({
              type: "about:blank",
              title: "Could not join",
              status: 0,
              detail: "The waiting room could not be joined.",
              code: "CLIENT_ERROR"
            })
      );
    } finally {
      setJoining(false);
    }
  };

  return (
    <Container maxWidth="sm" sx={{ py: 6 }}>
      <Stack spacing={3}>
        <EventHeader event={event} />
        {joinError && <ProblemAlert message={joinError.message} />}
        <Stack spacing={1.5}>
          <Typography component="h2" variant="h5">
            Tiers
          </Typography>
          {event.tiers.map((tier) => (
            <TierCard
              key={tier.tierId}
              tier={tier}
              selected={selectedTierId === tier.tierId}
              onSelect={(selected) => setSelectedTierId(selected.tierId)}
            />
          ))}
        </Stack>
        <Button
          variant="contained"
          size="large"
          disabled={!canJoin || joining}
          onClick={() => void handleJoin()}
        >
          {joining ? <CircularProgress size={22} color="inherit" /> : "Join Flash Sale"}
        </Button>
      </Stack>
    </Container>
  );
}

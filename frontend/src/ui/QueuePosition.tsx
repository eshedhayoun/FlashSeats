import Box from "@mui/material/Box";
import { keyframes } from "@mui/material/styles";
import Typography from "@mui/material/Typography";
import { useEffect, useRef, useState } from "react";
import { describePosition, formatPosition } from "../format/queue";
import { visuallyHidden } from "./VisuallyHidden";

const fadeIn = keyframes`
  from { opacity: 0.25; transform: translateY(4px); }
  to { opacity: 1; transform: none; }
`;

/** How often the position may be read out. A frame every two seconds is not news. */
const ANNOUNCE_EVERY_MS = 30_000;

/**
 * The position, large. It fades between values over 400 ms — a number that snaps looks like a
 * glitch — and is announced to a screen reader at most every 30 s, or at once when the buyer reaches
 * the front.
 */
export function QueuePosition({ position }: { position: number | null }) {
  const [spoken, setSpoken] = useState(describePosition(position));
  const lastSpokenAt = useRef(0);

  useEffect(() => {
    const now = Date.now();
    const atFront = position !== null && position <= 1;
    if (atFront || now - lastSpokenAt.current >= ANNOUNCE_EVERY_MS) {
      lastSpokenAt.current = now;
      setSpoken(describePosition(position));
    }
  }, [position]);

  return (
    <Box textAlign="center">
      <Typography
        key={position ?? "unknown"}
        component="div"
        aria-hidden
        className="tabular"
        data-testid="queue-position"
        sx={{
          fontSize: { xs: "3.5rem", sm: "4.5rem" },
          fontWeight: 800,
          letterSpacing: "-0.03em",
          lineHeight: 1.05,
          minWidth: "6ch",
          animation: `${fadeIn} 400ms ease-out`
        }}
      >
        {formatPosition(position)}
      </Typography>
      <Box component="span" sx={visuallyHidden} aria-live="polite">
        {spoken}
      </Box>
    </Box>
  );
}

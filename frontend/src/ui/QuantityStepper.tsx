import IconButton from "@mui/material/IconButton";
import Stack from "@mui/material/Stack";
import Typography from "@mui/material/Typography";
import AddRounded from "@mui/icons-material/AddRounded";
import RemoveRounded from "@mui/icons-material/RemoveRounded";

/** 1 … `max`, where `max` is the tier's own limit from the API — never a number of ours (FE_SPEC §0 rule 2). */
export function QuantityStepper({
  value,
  max,
  disabled,
  onChange
}: {
  value: number;
  max: number;
  disabled?: boolean;
  onChange: (value: number) => void;
}) {
  return (
    <Stack direction="row" alignItems="center" spacing={1} role="group" aria-label="Number of tickets">
      <IconButton
        aria-label="One fewer ticket"
        onClick={() => onChange(Math.max(1, value - 1))}
        disabled={disabled || value <= 1}
        sx={{ border: 1, borderColor: "divider" }}
      >
        <RemoveRounded />
      </IconButton>
      <Typography
        className="tabular"
        aria-live="polite"
        aria-label={`${value} ${value === 1 ? "ticket" : "tickets"}`}
        sx={{ minWidth: "3ch", textAlign: "center", fontSize: "1.25rem", fontWeight: 700 }}
      >
        {value}
      </Typography>
      <IconButton
        aria-label="One more ticket"
        onClick={() => onChange(Math.min(max, value + 1))}
        disabled={disabled || value >= max}
        sx={{ border: 1, borderColor: "divider" }}
      >
        <AddRounded />
      </IconButton>
    </Stack>
  );
}

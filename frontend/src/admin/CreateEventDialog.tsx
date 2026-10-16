import Button from "@mui/material/Button";
import CircularProgress from "@mui/material/CircularProgress";
import Dialog from "@mui/material/Dialog";
import DialogActions from "@mui/material/DialogActions";
import DialogContent from "@mui/material/DialogContent";
import DialogTitle from "@mui/material/DialogTitle";
import IconButton from "@mui/material/IconButton";
import Stack from "@mui/material/Stack";
import TextField from "@mui/material/TextField";
import Typography from "@mui/material/Typography";
import AddRounded from "@mui/icons-material/AddRounded";
import DeleteOutlineRounded from "@mui/icons-material/DeleteOutlineRounded";
import { useEffect, useState, type FormEvent } from "react";
import { asApiError } from "../api/errors";
import { Notice } from "../ui/Notice";
import type { CreateEventRequest } from "./adminApi";
import {
  defaultForm,
  emptyTier,
  MAX_PER_ORDER,
  MAX_TIERS,
  toCreateRequest,
  type EventForm,
  type TierForm
} from "./eventForm";

const TIER_FIELDS: { field: keyof TierForm; label: string; inputMode?: "decimal" | "numeric"; maxLength?: number }[] = [
  { field: "tierName", label: "Name", maxLength: 100 },
  { field: "price", label: "Price (USD)", inputMode: "decimal" },
  { field: "totalCapacity", label: "Capacity", inputMode: "numeric" },
  { field: "maxPerOrder", label: `Per order (1–${MAX_PER_ORDER})`, inputMode: "numeric" }
];

/**
 * Creates a sale: the event and every tier at once, published and pre-warmed by the server. There is
 * no edit afterwards, so the form says what it cannot change later (ADR-080).
 */
export function CreateEventDialog({
  open,
  onClose,
  onSubmit
}: {
  open: boolean;
  onClose: () => void;
  onSubmit: (request: CreateEventRequest) => Promise<void>;
}) {
  const [form, setForm] = useState<EventForm>(() => defaultForm(new Date()));
  const [error, setError] = useState<string[] | null>(null);
  const [busy, setBusy] = useState(false);

  // Fresh defaults on every open: the dialog stays mounted, and defaults computed at sign-in would
  // fail the "at least 2 minutes ahead" check once the console has been open that long.
  useEffect(() => {
    if (open) {
      setForm(defaultForm(new Date()));
      setError(null);
    }
  }, [open]);

  const set = (field: keyof Omit<EventForm, "tiers">) => (value: string) =>
    setForm((current) => ({ ...current, [field]: value }));
  const setTier = (index: number, field: keyof TierForm) => (value: string) =>
    setForm((current) => ({
      ...current,
      tiers: current.tiers.map((tier, i) => (i === index ? { ...tier, [field]: value } : tier))
    }));

  const close = () => {
    if (!busy) onClose();
  };

  async function submit(event: FormEvent) {
    event.preventDefault();
    const result = toCreateRequest(form, new Date());
    if ("error" in result) {
      setError([result.error]);
      return;
    }
    // Not idempotent: a second click would make a second sale, so the button is off while this runs.
    setBusy(true);
    setError(null);
    try {
      await onSubmit(result.request);
      onClose();
    } catch (cause) {
      const problem = asApiError(cause).problem;
      setError([problem.detail, ...(problem.violations ?? []).map((v) => `${v.field}: ${v.message}`)]);
    } finally {
      setBusy(false);
    }
  }

  return (
    <Dialog open={open} onClose={close} maxWidth="md" fullWidth aria-labelledby="create-event-title">
      <form onSubmit={submit} noValidate>
        <DialogTitle id="create-event-title" sx={{ fontWeight: 700 }}>
          New sale
        </DialogTitle>
        <DialogContent>
          <Stack spacing={2} sx={{ pt: 1 }}>
            <Typography variant="body2" color="text.secondary">
              The sale is published and pre-warmed as soon as you create it. It cannot be edited
              afterwards; until a minute before it opens you can delete it and create it again.
            </Typography>
            <TextField
              label="Title"
              required
              value={form.title}
              onChange={(e) => set("title")(e.target.value)}
              helperText="Ticket PDFs print Latin characters only."
              slotProps={{ htmlInput: { maxLength: 255 } }}
            />
            <TextField
              label="Venue"
              required
              value={form.venueName}
              onChange={(e) => set("venueName")(e.target.value)}
              slotProps={{ htmlInput: { maxLength: 255 } }}
            />
            <TextField
              label="Description"
              multiline
              minRows={2}
              value={form.description}
              onChange={(e) => set("description")(e.target.value)}
              slotProps={{ htmlInput: { maxLength: 2000 } }}
            />
            <Stack direction={{ xs: "column", sm: "row" }} spacing={2}>
              {(
                [
                  ["saleStartTime", "Sale opens"],
                  ["saleEndTime", "Sale closes"],
                  ["eventStartTime", "Event starts"]
                ] as const
              ).map(([field, label]) => (
                <TextField
                  key={field}
                  label={label}
                  type="datetime-local"
                  required
                  fullWidth
                  value={form[field]}
                  onChange={(e) => set(field)(e.target.value)}
                  slotProps={{ inputLabel: { shrink: true } }}
                />
              ))}
            </Stack>

            <Typography variant="subtitle1" component="h3" sx={{ fontWeight: 700 }}>
              Tiers
            </Typography>
            {form.tiers.map((tier, index) => (
              <Stack key={index} direction={{ xs: "column", sm: "row" }} spacing={1.5} sx={{ alignItems: "center" }}>
                {TIER_FIELDS.map(({ field, label, inputMode, maxLength }) => (
                  <TextField
                    key={field}
                    label={label}
                    size="small"
                    required
                    fullWidth={field === "tierName"}
                    value={tier[field]}
                    onChange={(e) => setTier(index, field)(e.target.value)}
                    slotProps={{ htmlInput: { inputMode, maxLength } }}
                  />
                ))}
                <IconButton
                  aria-label={`Remove tier ${index + 1}`}
                  disabled={form.tiers.length === 1}
                  onClick={() => setForm((current) => ({ ...current, tiers: current.tiers.filter((_, i) => i !== index) }))}
                >
                  <DeleteOutlineRounded />
                </IconButton>
              </Stack>
            ))}
            <div>
              <Button
                startIcon={<AddRounded />}
                disabled={form.tiers.length >= MAX_TIERS}
                onClick={() => setForm((current) => ({ ...current, tiers: [...current.tiers, emptyTier()] }))}
              >
                Add tier
              </Button>
            </div>

            {error && (
              <Notice severity="error">
                {error.map((line) => (
                  <div key={line}>{line}</div>
                ))}
              </Notice>
            )}
          </Stack>
        </DialogContent>
        <DialogActions sx={{ px: 3, pb: 2.5, gap: 1 }}>
          <Button onClick={close} disabled={busy}>
            Cancel
          </Button>
          <Button
            type="submit"
            variant="contained"
            disabled={busy}
            startIcon={busy ? <CircularProgress size={16} color="inherit" /> : undefined}
          >
            Create sale
          </Button>
        </DialogActions>
      </form>
    </Dialog>
  );
}

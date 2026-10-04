import Button from "@mui/material/Button";
import ContentCopyRounded from "@mui/icons-material/ContentCopyRounded";
import CheckRounded from "@mui/icons-material/CheckRounded";
import { useEffect, useState } from "react";

export function CopyButton({ value, label }: { value: string; label: string }) {
  const [state, setState] = useState<"idle" | "copied" | "failed">("idle");

  useEffect(() => {
    if (state === "idle") return;
    const timer = window.setTimeout(() => setState("idle"), 2_000);
    return () => window.clearTimeout(timer);
  }, [state]);

  const copy = async () => {
    try {
      await navigator.clipboard.writeText(value);
      setState("copied");
    } catch {
      setState("failed");
    }
  };

  return (
    <Button
      size="small"
      variant="outlined"
      onClick={() => void copy()}
      startIcon={state === "copied" ? <CheckRounded /> : <ContentCopyRounded />}
      aria-label={state === "copied" ? `${label} copied` : `Copy ${label}`}
      aria-live="polite"
    >
      {state === "copied" ? "Copied" : state === "failed" ? "Couldn't copy" : "Copy"}
    </Button>
  );
}

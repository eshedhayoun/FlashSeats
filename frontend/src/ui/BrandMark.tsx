import Box from "@mui/material/Box";
import Typography from "@mui/material/Typography";
import { HERO_GRADIENT } from "../app/theme";

/** The bolt in a gradient tile, and the wordmark. Decorative: the link around it carries the name. */
export function BrandMark() {
  return (
    <Box sx={{ display: "flex", alignItems: "center", gap: 1.25 }}>
      <Box
        aria-hidden
        sx={{
          width: 32,
          height: 32,
          borderRadius: 2,
          background: HERO_GRADIENT,
          display: "grid",
          placeItems: "center",
          flexShrink: 0
        }}
      >
        <svg width="18" height="18" viewBox="0 0 24 24" fill="#FFFFFF" focusable="false">
          <path d="M13.5 2 4 13.5h6.5L9 22l11-12.5h-6.75L13.5 2Z" />
        </svg>
      </Box>
      <Typography component="span" sx={{ fontWeight: 800, fontSize: "1.15rem", letterSpacing: "-0.02em" }}>
        FlashSeats
      </Typography>
    </Box>
  );
}

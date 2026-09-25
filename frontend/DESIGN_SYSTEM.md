

---

## 10. Design System

FlashSeats uses a cohesive, dark-mode-first design with warm mint-greens that feels contemporary and calm during high-stress moments. This section specifies the visual foundation: colour tokens, type scale, and component patterns that maintain consistency across all views.

### Palette tokens

**Primary brand colour**
- `#65d391` — Mint green, used for CTAs, positive states, success indicators
- Contrast text: `#102017` (near-black, ensures WCAG AA on mint backgrounds)

**Secondary colour**
- `#9be7b2` — Lighter mint, used for supporting elements, secondary CTAs

**Background**
- Default: `#252a27` — Deep grey-green, page background
- Paper/Card: `#303732` — Slightly lighter grey-green, cards/panels

**Text**
- Primary: `#b9f2c8` — Light mint, body text, high contrast on dark backgrounds
- Secondary: `#a8c5b1` — Muted mint, labels, hints, secondary copy

**Semantic colours**
- Error: `#ff6b6b` — Red, for errors and critical states
- Warning: `#ffd93d` — Amber, for warnings and caution states (e.g., hold expiry approaching)
- Success: `#65d391` — Mint (same as primary)
- Info: `#4ecdc4` — Cyan, for informational states

**Divider/border**
- `#4a5d50` — Mid-tone grey-green, used for dividers and subtle borders

### Type scale

All typography uses the system font stack: `system-ui, -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif`

| Scale | Size | Weight | Usage |
|-------|------|--------|-------|
| **h1** | 48px | 600 | Page titles ("Aurora Fest 2026"), queue position ("#128") |
| **h2** | 36px | 600 | Section headers ("Tiers", "Live availability") |
| **h3** | 28px | 600 | Subheadings, modal titles |
| **h4** | 24px | 600 | Card titles ("Complete your purchase") |
| **h5** | 20px | 500 | Subsection headers |
| **h6** | 16px | 500 | Small headers, form labels |
| **body1** | 16px | 400 | Body text, default paragraph text |
| **body2** | 14px | 400 | Secondary body, supporting text |
| **subtitle1** | 16px | 500 | Intro text, emphasis |
| **subtitle2** | 14px | 500 | Category labels, section subtitles |
| **caption** | 12px | 400 | Timestamps, helper text, fine print |
| **overline** | 11px | 600 | All-caps labels (rare) |

**Line height**: 1.5 for body text, 1.2 for headings (ensures readability on dark backgrounds)

**Letter spacing**: No additional tracking except for overline (+0.1em)

### Countdown timers — tone-based styling

The `<Countdown />` component emits urgency through colour and animation. This is the single most important visual pattern for managing buyer anxiety during checkout.

| Tone | Time remaining | Colour | Animation | Purpose |
|------|----------------|--------|-----------|---------|
| **neutral** | > 2 min | Secondary text (`#a8c5b1`) | None | Early stages, no urgency |
| **warning** | 1–2 min | Warning (`#ffd93d`) | None | Visible colour shift, invites attention |
| **critical** | < 1 min | Error (`#ff6b6b`) | Gentle pulse (opacity 0.7 at 50%) | Urgent, demands focus |

**Pulse animation** — only on `critical` tone:
```css
@keyframes pulse {
  0%, 100% { opacity: 1; }
  50% { opacity: 0.7; }
}
```

**Accessibility**: Respects `prefers-reduced-motion` — no animation on critical tone if user has set that preference. Colour alone does not convey state; accompanying text like "**< 1 min remaining**" must always be present.

### Component patterns

#### Button states

| State | Background | Text | Border | Cursor | Usage |
|-------|-----------|------|--------|--------|-------|
| **enabled** | `#65d391` | `#102017` | None | pointer | Primary CTAs |
| **hover** | `#55c381` | `#102017` | None | pointer | Visual feedback |
| **active/pressed** | `#45b371` | `#102017` | None | pointer | Click feedback |
| **disabled** | `#4a5d50` | `#808a84` | None | not-allowed | Inactive (e.g., form incomplete) |
| **loading** | `#65d391` | `#102017` | None | wait | Spinner overlays button text |

**Outlined buttons**: No fill, `#65d391` border, same text colour.

#### Form inputs

- **Border**: `#4a5d50` (divider colour)
- **Focus**: `#65d391` border, +2px width
- **Placeholder**: `#a8c5b1` (secondary text)
- **Text**: `#b9f2c8` (primary text)
- **Error**: Red border + error message in red
- **Disabled**: Greyed-out text and border

#### Cards

- **Background**: `#303732` (paper)
- **Padding**: 24px (standard Material Design)
- **Border**: 1px `#4a5d50`
- **Shadow**: Subtle (elevation 1): `0 2px 4px rgba(0, 0, 0, 0.3)`

#### Status badges

| Status | Colour | Example |
|--------|--------|---------|
| Available/Success | `#65d391` | "Available" (tier badge) |
| Limited | `#ffd93d` | "Limited" (tier badge) |
| Sold Out | `#a8c5b1` (secondary/disabled) | "Sold Out" (tier badge) |
| Unknown/Checking | `#4ecdc4` | "Checking…" (tier badge) |
| Error | `#ff6b6b` | "Connection lost" (status indicator) |

#### Queue position display

- **Font**: h1 (48px, 600 weight), `tabular-nums` variant
- **Colour**: Primary text (`#b9f2c8`)
- **Layout**: Centered, 1em top/bottom padding
- **Update animation**: 400ms opacity fade on position change (not instant snap)

#### Waiting room (V2)

- **Title**: h3, secondary text colour (`#a8c5b1`)
- **Position**: h1, primary text
- **Timer**: body1, tertiary text, 400ms update fade
- **Connection status**: subtitle2, muted, italic ("Reconnecting — your place is saved")
- **Tier availability**: Each tier name in secondary text, level in colour (available/limited/sold out)

### Layout & spacing

**Container max-width**: `640px` (sm) for single-column flows (landing, queue, checkout)

**Spacing scale**:
- 8px (1 unit) — micro interactions
- 16px (2 units) — component internal padding
- 24px (3 units) — card padding, standard block spacing
- 32px (4 units) — section spacing
- 64px (8 units) — page vertical padding

**Stack gaps**: Use consistent 24px between major sections, 16px between minor items

### Accessibility requirements

1. **Contrast**: All text meets WCAG AA (4.5:1 for body text, 3:1 for large text)
   - Test each mint-on-grey combination
   - Error and warning colours tested against white and dark backgrounds

2. **Focus indicators**: 2px border in mint (`#65d391`), visible on all interactive elements

3. **Motion**: `prefers-reduced-motion` disables all animations except page transitions

4. **Icons**: Paired with text labels; no icon-only buttons on critical paths

5. **Typography**: No text smaller than 14px (except captions at 12px)

### Implementation notes

The theme is built with **Material-UI (MUI) createTheme()** in `frontend/src/app/theme.ts`. Extend the theme file to override component defaults (button styles, card elevation, etc.).

**Countdown tone colours**: The Countdown component reads theme colours via `useTheme()` and applies tone-based styling inline (error/warning/neutral). The component respects `prefers-reduced-motion` for animations.

**Dark mode only**: No light mode variant is currently designed. If light mode is required in future, create a complementary palette with sufficient contrast.

---

**Design system approved for Phase 4 Stage 4b. Mint palette, warm dark mode, countdown urgency tones, and accessible components are production-ready.**

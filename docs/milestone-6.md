# Milestone 6 — Dashboard Walkthrough

_A junior-engineer-level walkthrough of the aggregate-stats endpoint and the first real charts in this project. Companion to `docs/milestone-0.md` through `docs/milestone-5.md` — read those first if you haven't._

---

## What M6 delivers

`DESIGN.md`'s roadmap (§11): "Dashboard — Aggregate query endpoint + simple charts on frontend." Backend and frontend together, one pass — the first milestone with an explicit frontend line since M3.

- `GET /api/dashboard/summary` — total applications, a breakdown by status, a response rate, and a 12-week applications-per-week trend, all scoped to the calling user
- A **Dashboard** tab in the frontend (new: a tab bar at all — before this milestone the app only ever had one screen), showing two stat tiles and two charts
- Both charts hand-rolled in plain CSS/HTML, no charting library added

---

## The aggregation — `DashboardService.java`

No new entity, no new migration — this milestone is pure read-side aggregation over data that already exists. `DashboardService` reuses `JobApplicationRepository.findAllByUserIdOrderByCreatedAtDesc` (the same method `JobApplicationService` has used since M2) and computes everything else in plain Java:

```java
public DashboardSummaryResponse getSummary(String email) {
    User user = userRepository.findByEmail(email).orElseThrow(...);
    List<JobApplication> applications = jobApplicationRepository.findAllByUserIdOrderByCreatedAtDesc(user.getId());

    return new DashboardSummaryResponse(
            applications.size(),
            statusBreakdown(applications),
            responseRate(applications),
            weeklyTrend(applications)
    );
}
```

**Why compute in Java instead of a grouped SQL query?** Postgres could do `GROUP BY status` and `date_trunc('week', applied_date)` natively, and for a system with millions of rows that would matter. `DESIGN.md` §2 names the actual scale target for this project explicitly: "hundreds of applications." Streaming a few hundred already-fetched entities through Java's `Stream`/`Map` APIs is trivially fast at that scale, avoids a second Postgres-specific query style alongside the plain derived-query methods used everywhere else in this codebase, and keeps every existing repository method reusable as-is.

Three things worth explaining:

- **`statusBreakdown` always returns all 7 statuses, zero-filled.** An `EnumMap<ApplicationStatus, Long>` is pre-populated with every enum value at `0L` before counting, so a user with no `WITHDRAWN` applications still gets `"WITHDRAWN": 0` in the response rather than a missing key. The frontend chart (below) needs this — it renders one row per status unconditionally, and a missing key would mean special-casing "what if this status has no data" in the UI instead of in one place here.
- **Response rate: any status other than `APPLIED` counts as "responded."** This treats a rejection as a response (a real signal came back, even if it's bad news), and only the literal "still just sitting there" state counts against the rate. Zero applications returns `0.0`, not a division-by-zero crash.
- **The weekly trend always returns exactly 12 points, one per week, zero-filled, ending at the current week.** Applications are bucketed by the Monday-aligned start of the week containing their `appliedDate` (`java.time.temporal.TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)`), counted into a map, and then a fixed 12-week window is built and every week looked up against that map with `getOrDefault(week, 0L)`. Applications with a `null` `appliedDate` (the field is nullable, per M2) are simply skipped from the trend — they still count toward the total and status breakdown, just not toward a week they were never dated for. Without the zero-fill, a frontend chart would have to guess which weeks to draw axis ticks for; with it, the x-axis is always exactly 12 evenly-spaced points.

`DashboardController` is two lines of routing (`GET /api/dashboard/summary` → `principal.getUsername()` → the service) — nothing new to explain there; it's the same `@AuthenticationPrincipal` pattern every controller has used since M1.

---

## Choosing the chart form and color — the dataviz skill

Before writing any chart code, this milestone used the project's `dataviz` skill, which insists on a specific order: pick the form, *then* assign color, then **validate the color computationally** rather than eyeballing it.

**Form.** Two different jobs, two different chart types:
- Status breakdown — 7 fixed categories in a meaningful order (`APPLIED → ONLINE_ASSESSMENT → PHONE_SCREEN → ONSITE → OFFER → REJECTED → WITHDRAWN` is a pipeline, not an arbitrary list — swapping the order would change what it means). The skill's own color-formula guidance names this exact scenario — "funnel stages" — as the textbook case for an **ordinal** one-hue ramp, not a multi-hue categorical palette. So: horizontal bars, one violet hue at seven increasing intensities, no legend needed (every bar already carries its own text label).
- Weekly trend — one series (a count) varying over time. Per the skill's own form table, "trend over time... one series" needs no color variation at all beyond a single consistent hue — so this is flat bars, all in the app's existing `--accent` color, with height as the only encoded variable.

**Color, computed, not eyeballed.** The skill ships a validator script (`validate_palette.js`) that checks a candidate palette against real, measurable rules: monotone lightness, minimum lightness gap between adjacent steps, contrast against the actual surface, and hue consistency. The first hand-picked 7-step violet ramp (evenly-spaced Tailwind-style swatches) **failed** two checks outright — the palest step was too close to white to read as a mark at all (1.19:1 contrast, need ≥2:1), and two adjacent steps were too close in lightness to look distinct. Both light-mode and dark-mode ramps went through several iterations, re-running the validator after each adjustment, until every check passed:

```
=== status ordinal, light ===
  [PASS] Lightness monotone     steps read light→dark
  [PASS] Adjacent ΔL            all gaps >= 0.06
  [PASS] Light-end contrast     #b3a1fa at 2.24:1 vs surface
  [PASS] Single hue             hue spread 4°
  → ALL CHECKS PASS

=== status ordinal, dark ===
  [PASS] Lightness monotone     steps read light→dark
  [PASS] Adjacent ΔL            all gaps >= 0.06
  [PASS] Light-end contrast     #5c4592 at 2.32:1 vs surface
  [PASS] Single hue             hue spread 4°
  → ALL CHECKS PASS
```

The final palettes landed in `index.css` as CSS custom properties (`--chart-ordinal-1` through `--chart-ordinal-7`, one set per color scheme), the same pattern the app already uses for `--accent`/`--bg`/etc. — so light/dark swapping is automatic and the chart components never touch a raw hex value.

```css
--chart-ordinal-1: #b3a1fa; /* APPLIED    -- lightest, recedes toward the surface */
--chart-ordinal-7: #33196f; /* WITHDRAWN  -- most saturated */
```

---

## The charts — plain CSS, no new dependency

`DESIGN.md`'s own wording for this milestone is "**simple** charts," and the app doesn't have a charting library yet. Rather than add one (a decision on the same order as the earlier `axios` choice), both charts are hand-rolled with `<div>`s and CSS, following the mark specs the dataviz skill lays out even though nothing here is literally SVG:

```css
.hbar-track   { height: 20px; border-radius: 4px; overflow: hidden; }        /* <=24px thick */
.hbar-fill    { border-radius: 0 4px 4px 0; min-width: 3px; }                 /* rounded data-end, square at baseline */
.hbar-chart   { display: flex; flex-direction: column; gap: 2px; }            /* 2px surface gap between bars */

.vbar-track   { max-width: 24px; }                                            /* <=24px thick */
.vbar-fill    { border-radius: 4px 4px 0 0; background: var(--accent); }      /* rounded top, square at baseline */
.vbar-chart   { gap: 2px; border-bottom: 1px solid var(--chart-grid); }       /* baseline hairline */
```

`StatusBreakdownChart.jsx` renders one row per status in fixed pipeline order, each bar's width scaled to the max count across all 7 (so the largest bar always fills the track, not an arbitrary fixed scale), color pulled from `var(--chart-ordinal-${i + 1})`. `WeeklyTrendChart.jsx` renders one column per week, height scaled to the max count across the 12 weeks, value labeled above the bar only when non-zero (an empty week shows an empty bar, not a redundant "0" floating above it — matching the skill's "label selectively, never a number on every point" rule, though here it's inverted: the *zero* values are the ones not worth labeling).

Two rules from the skill's `marks-and-anatomy.md` show up directly: **text never wears the data color** (every label — status names, counts, week dates — uses `var(--text)`/`var(--text-h)`, never the violet ramp itself, which would be illegible for the lighter steps against a light background anyway), and **a legend is skipped entirely** for both charts — the ordinal chart's bars are each already directly labeled with their category name, and the trend chart is a single series where the card's own title (`"Applications per week"`) already says what's plotted.

---

## Wiring it into the app — the first real navigation

Every milestone before this one had exactly one screen once logged in. Adding a second screen meant the header (title, user info, logout button) that used to live inside `ApplicationsPage.jsx` needed to become shared, not duplicated. `components/AppShell.jsx` is new — a genuinely feature-agnostic component (per `DESIGN.md` §10's planned `components/` folder), owning the header, a two-tab nav bar (`Applications` / `Dashboard`), and rendering whichever page is active as `children`:

```jsx
function AppContent() {
  const { token } = useAuth()
  const [view, setView] = useState('applications')

  if (!token) return <AuthForm />

  return (
    <AppShell view={view} onViewChange={setView}>
      {view === 'applications' ? <ApplicationsPage /> : <DashboardPage />}
    </AppShell>
  )
}
```

`ApplicationsPage.jsx` had its `<header>` block deleted and its own top-level `<div className="page">` wrapper removed — `AppShell` owns both now; `ApplicationsPage` is just its toolbar, list, and modals. Still no `react-router-dom` — same decision as M2, revisited and reaffirmed here rather than silently carried over: with exactly two screens and no deep-linkable URLs needed, a `useState` toggle inside `AppShell` costs nothing and adds no dependency. Real routing stays M8's job.

---

## Verification

Backend aggregation was checked by hand-computing the expected numbers, not just trusting the response shape:

| Check | Expected | Got |
|---|---|---|
| Fresh user, zero applications | `total: 0`, all 7 statuses `0`, `responseRate: 0.0`, 12 zero-count weeks (no crash) | ✅ |
| 6 applications created: `APPLIED`×2, `PHONE_SCREEN`×1, `ONSITE`×1, `OFFER`×1, `REJECTED`×1, dated across 3 different weeks | `total: 6` | ✅ |
| Status breakdown | `APPLIED: 2, PHONE_SCREEN: 1, ONSITE: 1, OFFER: 1, REJECTED: 1`, others `0` | ✅ |
| Response rate | `4/6 = 0.6667` | ✅ (matched exactly) |
| Weekly trend | 2026-06-10 → week `2026-06-08` (2 apps), 2026-06-24 → week `2026-06-22` (2 apps), 2026-07-01 → week `2026-06-29` (2 apps), all other weeks `0` | ✅ (hand-verified each date's Monday-aligned bucket) |

Frontend verified with the same Playwright-driver approach every milestone since M2 has used: logged in as the seeded test user, clicked into the Dashboard tab, read the stat tile values and DOM row/column counts back out (`6`, `67%`, 7 status rows, 12 week columns — matching the backend numbers above), confirmed zero console/page errors, and — since this milestone is specifically about visual output — took real screenshots in both light and dark mode and looked at them directly rather than trusting DOM assertions alone. Both render cleanly: the ordinal ramp reads correctly light-to-dark across the 7 status bars, the three non-zero weeks land in the right x-axis positions, and the dark-mode ramp swaps in automatically via the CSS custom properties with no separate logic.

---

## What's next

M7 (Search/filter/pagination) per the roadmap: query parameters on the applications list endpoint (`status`, `company`, date range) and matching frontend controls — the first milestone that revisits `GET /api/applications` itself rather than adding a new resource or read-model alongside it.

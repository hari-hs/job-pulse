# Milestone 7 — Search, Filter, Pagination Walkthrough

_A junior-engineer-level walkthrough of the first milestone that revisits an existing endpoint instead of adding a new one. Companion to `docs/milestone-0.md` through `docs/milestone-6.md` — read those first if you haven't._

---

## What M7 delivers

`DESIGN.md`'s roadmap (§11): "Search/filter/pagination — Query params on list endpoint, frontend controls." Every milestone before this one added a new resource or a new read-model. This one changes `GET /api/applications` itself — the same endpoint M2 built — to accept optional query parameters and return a paginated shape instead of a bare array.

- `GET /api/applications?status=...&company=...&dateFrom=...&dateTo=...&page=...&size=...&sort=...`
- Any combination of filters, or none at all
- A real pagination envelope (`content`, `page`, `size`, `totalElements`, `totalPages`) instead of a plain list
- Matching frontend: a filter bar, a "no results match your filters" distinct empty state, and Previous/Next pagination controls

---

## Why `Specification`, not more derived-query methods

Every repository method in this codebase until now has been a Spring Data **derived query** — `findByIdAndUserId`, `findByApplicationIdOrderByRemindAtAsc`, and so on — where the method name itself describes the exact query. That style breaks down here: with four independent optional filters (`status`, `company`, `dateFrom`, `dateTo`), the possible combinations of "which filters are present" number in the double digits, and Spring Data has no derived-query syntax for "this predicate only if the value is non-null."

The alternative that was actually used is `Specification<JobApplication>` — part of `spring-data-jpa` already on the classpath, not a new dependency. `JobApplicationSpecifications.java` is a small set of one-predicate-each static factories:

```java
public static Specification<JobApplication> ownedBy(Long userId) {
    return (root, query, cb) -> cb.equal(root.get("user").get("id"), userId);
}

public static Specification<JobApplication> hasStatus(ApplicationStatus status) {
    return (root, query, cb) -> cb.equal(root.get("status"), status);
}

public static Specification<JobApplication> companyContains(String company) {
    return (root, query, cb) -> cb.like(cb.lower(root.get("companyName")), "%" + company.toLowerCase() + "%");
}
```

`JobApplicationService.search(...)` builds the final query by starting from `ownedBy(userId)` — **always present, never optional** — and `.and()`-ing in each filter only when its value was actually supplied:

```java
Specification<JobApplication> spec = JobApplicationSpecifications.ownedBy(user.getId());
if (status != null) spec = spec.and(JobApplicationSpecifications.hasStatus(status));
if (company != null && !company.isBlank()) spec = spec.and(JobApplicationSpecifications.companyContains(company));
// ...
Page<JobApplication> page = jobApplicationRepository.findAll(spec, pageable);
```

The ownership predicate being unconditional and first is the one line in this milestone that matters most for correctness — every other predicate is optional, but this one never is. `JobApplicationRepository` picked up `JpaSpecificationExecutor<JobApplication>` (alongside its existing `JpaRepository<JobApplication, Long>`) to get the `findAll(Specification, Pageable)` method this all runs through.

`companyContains` is a case-insensitive substring match (`LIKE '%...%'` on the lowercased column), not an exact filter — `DESIGN.md` §1 calls this feature "search," and a search box that only matches exact, case-sensitive company names would surprise almost anyone using it.

---

## Pagination — `Pageable`, `PageResponse`, and one thing deliberately not exposed

Spring Boot auto-configures a `Pageable` argument resolver the moment `spring-boot-starter-data-jpa` and web support are both present — nothing extra to wire up. The controller just declares the parameter with a default:

```java
@PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable
```

That default (`?page=0&size=20&sort=createdAt,desc` if nothing is specified) reproduces exactly the ordering M2's original unpaginated endpoint always used, so a client that never sends pagination params sees the same first page it always would have.

**What never leaves the backend: Spring Data's own `Page`/`PageImpl` type.** Its JSON shape isn't something this API controls or wants to depend on — the same "DTOs at the boundary" rule `DESIGN.md` §3 has applied to every entity since M1 applies here too. `PageResponse<T>` (in `com.jobpulse.common`, since it's generic enough for any future paginated endpoint, not applications-specific) is the hand-shaped equivalent:

```java
public record PageResponse<T>(List<T> content, int page, int size, long totalElements, int totalPages) {
    public static <T> PageResponse<T> from(Page<T> page) {
        return new PageResponse<>(page.getContent(), page.getNumber(), page.getSize(), page.getTotalElements(), page.getTotalPages());
    }
}
```

One small defensive addition: if a client requests a page `size` over 100, the service clamps it back down before running the query (`PageRequest.of(pageable.getPageNumber(), 100, pageable.getSort())`) — cheap insurance against a client (malicious or just buggy) asking for `size=999999` and forcing one query to load and serialize everything a user has ever created.

---

## Two new error cases, and a Boot 4 package-location surprise

A filterable, sortable, paginated endpoint has more ways for a client to send something invalid than any endpoint before it. Two new handlers went into `GlobalExceptionHandler`:

```java
@ExceptionHandler(MethodArgumentTypeMismatchException.class)
public ResponseEntity<ErrorResponse> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
    return ResponseEntity.badRequest().body(new ErrorResponse("%s: invalid value %s".formatted(ex.getName(), ex.getValue())));
}

@ExceptionHandler(PropertyReferenceException.class)
public ResponseEntity<ErrorResponse> handleInvalidSortField(PropertyReferenceException ex) {
    return ResponseEntity.badRequest().body(new ErrorResponse("Invalid sort field: " + ex.getPropertyName()));
}
```

The first catches `?status=NOT_A_REAL_STATUS` — Spring tries to convert the query string to the `ApplicationStatus` enum, fails, and without this handler the client would see a generic `500` instead of a clean `400`. The second catches `?sort=someFieldThatDoesntExist` — Spring Data validates a sort property lazily, at query-execution time, not at binding time, so an invalid field name surfaces as a `PropertyReferenceException` thrown from deep inside the JPA layer.

Finding that second exception's actual package was this milestone's own small Boot-4 gotcha, in the same family as M1's Flyway-starter surprise: it used to live at `org.springframework.data.mapping.PropertyReferenceException` in older Spring Data, and the first compile attempt used exactly that import — which failed, because in the version bundled with Boot 4.1 (`spring-data-commons` 4.1.0) it had moved to `org.springframework.data.core.PropertyReferenceException`. Checked directly against the actual jar contents (`unzip -l` on the dependency), not assumed from memory of older versions — the same discipline M1 already established for exactly this kind of surprise.

---

## Frontend: filters, pagination, and a distinguishing empty state

`ApplicationsPage.jsx` now owns two new pieces of state — `filters` (company/status/dateFrom/dateTo) and `page` — both fed into `getApplications({ ...filters, page })`, which builds the query string via axios's `params` option. Changing any filter resets `page` back to `0`:

```jsx
function handleFiltersChange(next) {
  setFilters(next)
  setPage(0) // a changed filter invalidates the current page position
}
```

Without that reset, filtering down to a smaller result set while sitting on, say, page 3 would silently show an empty page even though matching results exist on page 1 — a real, easy-to-hit bug if this line were missing.

`ApplicationFilters.jsx` is a plain controlled form (text input for company, a status `<select>`, two date inputs) that reports changes upward via a single `onChange` callback — no local state of its own, matching the "lift state up" pattern already used everywhere else in this app (`ApplicationForm`, `AuthForm`). `PaginationControls.jsx` renders nothing at all when `totalElements` is `0` (an empty result doesn't need a "Page 1 of 1" line cluttering the empty-state message above it), and disables Previous/Next at the boundaries rather than letting them silently no-op.

One small UX distinction worth calling out: the empty state now reads differently depending on *why* the list is empty — `"No applications yet. Add your first one above."` for a genuinely empty account versus `"No applications match your filters."` when filters are active but nothing matches:

```jsx
{filtersActive ? 'No applications match your filters.' : 'No applications yet. Add your first one above.'}
```

Telling those two situations apart matters: a brand-new user seeing "no applications yet" knows to click "+ New Application"; a user who just typed a company name that doesn't match anything needs to know their filter, not their data, is the reason the list is empty.

---

## Verification

Backend, checked directly against real data (5 applications created across 5 companies, 5 statuses, 5 dates):

| Check | Expected | Got |
|---|---|---|
| `?status=APPLIED` | 2 matches (the two `APPLIED` ones) | ✅ |
| `?company=acme` | 2 matches, case-insensitive substring (`Acme Corp`, `Acme Widgets`) | ✅ |
| `?dateFrom=2026-06-01&dateTo=2026-07-01` | 3 matches, date-range inclusive on both ends | ✅ |
| `?status=APPLIED&company=umbrella` (combined) | 1 match — filters AND together, not OR | ✅ |
| `?size=2&page=0` then `?size=2&page=1`, `sort=companyName,asc` | different, correctly-ordered items per page; `totalElements: 5`, `totalPages: 3` | ✅ |
| `?status=NOT_A_STATUS` | `400`, clean message naming the bad value | ✅ |
| `?sort=password,asc` | `400`, clean message naming the bad field | ✅ |
| A second user, any filter (including one that would match the first user's data) | always `0` results — ownership predicate always applies, filters never bypass it | ✅ |
| `?size=99999` | clamped to `100` in the response, no error | ✅ |
| `./mvnw test` | passes | ✅ |

That second-to-last row is the one worth dwelling on: a filter matching another user's company name still returns zero results for the wrong user, confirming `ownedBy(userId)` really is unconditional in the specification chain and not something a crafted query could route around.

Frontend verified the same way as every milestone since M2 — Playwright, with real screenshots reviewed directly: unfiltered list (5 rows, correct pagination text), filtered to 2 rows via the company search box, and the distinct "No applications match your filters" empty state when a filter matches nothing. Zero console or page errors.

---

## What's next

M8 (Frontend polish) per the roadmap: protected routes, real auth UX, and error states — the first milestone to revisit the deliberately-deferred `react-router-dom` decision from M2/M3/M6, now that there's enough surface area (auth screen, applications, dashboard) to make real routing worth its cost.

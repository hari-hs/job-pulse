# Milestone 1 — Auth Walkthrough

_A junior-engineer-level walkthrough of everything Milestone 1 added: every new file, why it exists, how the pieces fit together, and the two real bugs we hit and fixed along the way. Companion to `docs/milestone-0.md` — read that one first if you haven't; this picks up where it left off._

---

## What M1 delivers

Per `DESIGN.md`'s roadmap (§11), M1's scope is: **register/login, JWT issuance & validation, password hashing.** Concretely, by the end of this milestone:

- `POST /api/auth/register` — create an account, get back a JWT
- `POST /api/auth/login` — verify credentials, get back a JWT
- `GET /api/users/me` — a protected endpoint that only works with a valid JWT, proving the whole chain works
- A real `users` table, created by a **Flyway migration** instead of Hibernate guessing the schema
- Passwords are never stored or logged in plaintext — only a BCrypt hash

**One deliberate scope decision, made explicitly before writing any code:** `DESIGN.md` §7 specs an access token *and* a refresh token (short-lived access token, longer-lived refresh token used to mint new ones). We simplified this down to **a single access token** with a longer expiry (24 hours) and no refresh flow at all. Refresh tokens solve a real problem — letting a user stay logged in for weeks without re-entering a password, while keeping the blast radius of a leaked token small — but they require token storage, rotation, and revocation logic that's real complexity for a personal project where auth isn't the interesting part. This is a documented deviation, not an oversight: if this were ever taken further (e.g. actually deployed and used daily), adding a refresh token later wouldn't break the API shape much — `AuthResponse` would just grow a second field.

---

## New dependencies — `backend/pom.xml`

Three things got added on top of M0's starters:

```xml
<dependency>
  <groupId>org.springframework.boot</groupId>
  <artifactId>spring-boot-starter-security</artifactId>
</dependency>
```
Pulls in Spring Security itself — the filter-chain framework, password encoders, the `AuthenticationManager`/`AuthenticationProvider` abstractions, everything `SecurityConfig` (below) builds on.

```xml
<dependency>
  <groupId>org.springframework.boot</groupId>
  <artifactId>spring-boot-starter-flyway</artifactId>
</dependency>
<dependency>
  <groupId>org.flywaydb</groupId>
  <artifactId>flyway-database-postgresql</artifactId>
</dependency>
```
Flyway — versioned, reviewable SQL migrations, replacing Hibernate's `ddl-auto` for schema changes (see the "How Flyway works" section below for *why* two dependencies, not one — it's the subject of Bug #1).

```xml
<dependency>
  <groupId>io.jsonwebtoken</groupId>
  <artifactId>jjwt-api</artifactId>
  <version>0.12.6</version>
</dependency>
<dependency>
  <groupId>io.jsonwebtoken</groupId>
  <artifactId>jjwt-impl</artifactId>
  <version>0.12.6</version>
  <scope>runtime</scope>
</dependency>
<dependency>
  <groupId>io.jsonwebtoken</groupId>
  <artifactId>jjwt-jackson</artifactId>
  <version>0.12.6</version>
  <scope>runtime</scope>
</dependency>
```
`jjwt` — a small, focused library for creating and parsing JWTs. Deliberately *not* `spring-security-oauth2-resource-server`, which is Spring's heavier machinery for validating tokens **issued by someone else** (Cognito, Auth0, Okta) — fetching their public keys over the network, handling key rotation, etc. We issue our own tokens and validate them with our own secret, so that entire apparatus is the wrong tool here. Notice this is the one dependency block with explicit version numbers — `jjwt` isn't part of the Spring Boot parent POM's managed dependency list (its "BOM," introduced in `docs/milestone-0.md`), so unlike everything else in this file, we're on the hook for picking and bumping this version ourselves.

The `api`/`impl`/`jackson` three-way split is `jjwt`'s own design: `jjwt-api` is the interface you code against, `jjwt-impl` is the actual implementation (marked `runtime` scope because your code never references its classes directly), and `jjwt-jackson` teaches it to serialize token claims as JSON using Jackson (which Spring Boot already has on the classpath) rather than pulling in a second JSON library.

---

## New config — `application.yml`

```yaml
jwt:
  # Dev-only default. Never use this value outside local development —
  # override with a real secret via the JWT_SECRET env var before deploying anywhere.
  secret: ${JWT_SECRET:dev-only-jwt-signing-secret-change-me-before-any-real-deployment}
  expiration-ms: ${JWT_EXPIRATION_MS:86400000} # 24 hours
```

Same `${VAR:default}` pattern M0 established for the datasource. With no env vars exported, `jwt.secret` falls back to the literal default string, and `jwt.expiration-ms` to 86,400,000 milliseconds (24 hours). This is exactly the seam `DESIGN.md` §12 depends on: setting `export JWT_SECRET=...` before starting the jar is the entire "production config" story for this value — no code changes, ever.

The secret needs to be reasonably long: it signs tokens with HMAC-SHA256, which requires a key of at least 256 bits (32 bytes) — `jjwt` throws a `WeakKeyException` at startup if the secret string is too short. The dev default is comfortably over that.

---

## How Flyway works (and Bug #1: it silently didn't)

**The idea:** instead of letting Hibernate guess the database schema from your `@Entity` classes (`ddl-auto: update`, which M0's walkthrough already flagged as dangerous once real data exists), you write plain `.sql` files, numbered in order, and a library applies them once, in order, tracking which ones have already run in a table it manages itself (`flyway_schema_history`). Whoever a new file is added — `V2__add_something.sql` — Flyway applies just that one on the next startup. This is what `DESIGN.md` §9's note about "an explicit migration tool, decided at M1" refers to.

**Where it lives:** `backend/src/main/resources/db/migration/V1__create_users_table.sql` — this exact path (`classpath:db/migration`, filenames matching `V<number>__<description>.sql`) is Flyway's default convention. No config needed for Flyway to find it; Spring Boot auto-wires a `Flyway` bean the moment it sees the library on the classpath and a `DataSource` bean already configured, and runs pending migrations *before* Hibernate even initializes.

```sql
CREATE TABLE users (
    id BIGSERIAL PRIMARY KEY,
    email VARCHAR(255) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    full_name VARCHAR(255),
    created_at TIMESTAMP NOT NULL DEFAULT now(),
    updated_at TIMESTAMP NOT NULL DEFAULT now()
);
```

One thing intentionally left out: a separate index on `email`. In Postgres, a `UNIQUE` constraint *is* a btree index — adding another one would just be redundant storage and write overhead for no benefit.

### Bug #1 — Flyway never ran, and said nothing about it

The first time the app was started with this migration in place, Flyway simply never executed it. Not "failed with an error" — **completely silent**. No `"Flyway Community Edition..."` banner, no `"Creating Schema History table"`, nothing — not even in Spring Boot's `--debug` auto-configuration report, which lists *every* auto-configuration class it considered, including the ones it skipped.

The first symptom was a `POST /api/auth/register` returning a bare `403 Forbidden` with an empty body — which briefly looked like a Spring Security misconfiguration (permitAll not matching, or CSRF still enabled). Chasing that red herring first (confirmed via `-Dlogging.level.org.springframework.security=TRACE` that CSRF genuinely was disabled, and that the filter chain was correctly matching the request) eventually led to the real stack trace, buried underneath a `Tomcat` internal forward to `/error`:

```
org.postgresql.util.PSQLException: ERROR: relation "users" does not exist
```

The `users` table was never created. Digging into why: `flyway-core` and `flyway-database-postgresql` were both correctly on the classpath (`mvn dependency:tree` confirmed it) — but **Spring Boot 4.1 removed `FlywayAutoConfiguration` from the core `spring-boot-autoconfigure` module entirely.** Boot 4 split it out into its own dedicated starter, `spring-boot-starter-flyway` — the exact same disaggregation pattern `docs/milestone-0.md` already flagged for `spring-boot-starter-webmvc` (replacing the Boot-3-era `spring-boot-starter-web`). Depending on `flyway-core` directly gets you the *library*, but not the piece of Spring Boot that notices it's there and wires up a bean to run it.

**The fix:** swap the raw `flyway-core` dependency for `spring-boot-starter-flyway` (keeping `flyway-database-postgresql` alongside it, since Postgres-specific support is *still* its own separate module even under the new starter). After that one change, the exact same migration file started working immediately — the log filled with the expected:

```
Schema history table "public"."flyway_schema_history" does not exist yet
Creating Schema History table "public"."flyway_schema_history" ...
Successfully applied 1 migration to schema "public", now at version v1
```

**The lesson, worth remembering for every future milestone:** Spring Boot 4 tutorials and Stack Overflow answers written against Boot 3 will keep assuming "just add the library's core jar and Boot autoconfigures it." That assumption needs to be re-verified against Boot 4.1's actual dependency list (`spring-boot-dependencies-4.1.0.pom` in the local Maven cache) before trusting it, the same way the `-webmvc` rename already taught us to double-check starter names.

### A second, smaller bug hiding inside the first

While that `users`-table error was happening, it surfaced as a `403`, not the `500` you'd expect for an unhandled server exception. That's because Tomcat's own error-handling machinery internally *forwards* the failed request to `/error` to render an error page — and `/error` wasn't in `SecurityConfig`'s `permitAll()` list. So Spring Security's `AuthorizationFilter` blocked the forward itself, and the *real* exception got swallowed underneath a second, misleading 403. Fix: add `/error` to the permitAll list, so error responses can actually render instead of being blocked by the same security layer that's supposed to protect real endpoints. Good general debugging note: **if a 403 shows up with no obvious cause, check whether it's really a masked exception being forwarded to `/error` and blocked on the way there** — Spring Security TRACE logs will show `ExceptionTranslationFilter` and `Http403ForbiddenEntryPoint` lines when this is happening.

---

## `com.jobpulse.user` — the entity and its supporting cast

**`User.java`** — a plain JPA entity, one row per account:

```java
@Entity
@Table(name = "users")
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String email;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Column(name = "full_name")
    private String fullName;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() { createdAt = updatedAt = Instant.now(); }

    @PreUpdate
    void onUpdate() { updatedAt = Instant.now(); }

    // plain getters/setters below
}
```

A few small choices worth explaining, since `DESIGN.md` left them open:

- **`Long id` with `IDENTITY` generation, not `UUID`.** `DESIGN.md` §4 listed "UUID/bigint" as an open option. UUIDs earn their keep in distributed systems (multiple services independently generating IDs that must never collide) or when you don't want IDs to be guessable/sequential in a public API. Neither applies here yet — this is one backend instance, and the "never trust an `id` in the URL alone" ownership check (§6) is what actually protects against IDOR-style bugs, not ID unguessability. Simple auto-increment `Long`s are easier to read in logs and curl output, so that's what we used. Nothing stops a later milestone from switching if the need shows up.
- **No Lombok.** Plain hand-written getters/setters instead of `@Data`/`@Getter` annotations. This is the *only* entity in the codebase right now, so pulling in a new build-time dependency (with its own annotation-processing setup) to save a dozen lines of boilerplate wasn't worth it yet.
- **`@PrePersist`/`@PreUpdate` instead of Spring Data JPA Auditing.** Spring Data has a whole auditing subsystem (`@EnableJpaAuditing`, `@CreatedDate`, `@LastModifiedDate`, an `AuditorAware` bean for tracking *who* made a change) — genuinely useful once multiple entities need timestamps and "who changed this" tracking. For one entity needing just two timestamps, two small `@PrePersist`/`@PreUpdate` methods do the same job with zero extra configuration.

**`UserRepository.java`** — a one-line Spring Data JPA interface:

```java
public interface UserRepository extends JpaRepository<User, Long> {
    Optional<User> findByEmail(String email);
    boolean existsByEmail(String email);
}
```

No implementation to write — Spring Data generates one at startup by parsing the method names (`findByEmail` → `WHERE email = ?`). `existsByEmail` is a small but real optimization over `findByEmail(...).isPresent()`: it compiles to a `SELECT 1 ... LIMIT 1`-style existence check rather than fetching (and Hibernate-hydrating) an entire row just to throw it away.

**`UserDetailsServiceImpl.java`** — the bridge between our `User` entity and Spring Security's world, which knows nothing about our domain model:

```java
@Service
public class UserDetailsServiceImpl implements UserDetailsService {

    private final UserRepository userRepository;

    @Override
    public UserDetails loadUserByUsername(String email) {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new UsernameNotFoundException("No user with email " + email));

        return org.springframework.security.core.userdetails.User
                .withUsername(user.getEmail())
                .password(user.getPasswordHash())
                .authorities(List.of(new SimpleGrantedAuthority("ROLE_USER")))
                .build();
    }
}
```

Spring Security's central contract for "how do I look up a user" is the `UserDetailsService` interface — one method, `loadUserByUsername`. We don't have usernames, only emails, so email plays double duty as Spring Security's "username" concept. Rather than writing a custom class that wraps our `User` entity and implements `UserDetails` (four extra methods, mostly returning `true`), we build Spring Security's own built-in `User` class (fully-qualified here to disambiguate from *our* `User` in the same file) — it already implements everything `UserDetails` needs. This class is `@Service`-annotated and picked up automatically: Spring Security auto-detects it as long as it's the only `UserDetailsService` bean in the context.

Every user gets a flat `ROLE_USER` authority. Nothing in the app checks roles yet (there's only one kind of user), but Spring Security expects *some* set of granted authorities, and this is cheap groundwork if role-based checks (e.g. an eventual admin role) ever become relevant.

**`UserService.java`** and **`UserController.java`** — a thin read-only slice exposing `GET /api/users/me`:

```java
@GetMapping("/me")
public UserResponse me(@AuthenticationPrincipal UserDetails principal) {
    return userService.getCurrentUser(principal.getUsername());
}
```

`@AuthenticationPrincipal` is Spring Security's way of injecting "whoever the current authenticated user is" directly into a controller method — it pulls the principal off the `SecurityContext` that `JwtAuthenticationFilter` (below) populated earlier in the request. `principal.getUsername()` here is the user's email (see above). `UserService.getCurrentUser` re-fetches the full `User` row by that email and maps it into a `UserResponse(email, fullName)` — a DTO, never the entity itself, keeping faith with `DESIGN.md` §3's "DTOs at the boundary" rule (the entity has a `passwordHash` field that must never leave the backend).

This endpoint isn't strictly named in M1's one-line roadmap description, but it's the only way to actually *prove* "JWT validation" works against a real protected resource — and `DESIGN.md` §6 already lists it as a genuine future endpoint, so this is pulling one small already-planned piece forward as a verification target, not scope creep.

---

## `com.jobpulse.auth` — issuing and validating tokens

**`JwtService.java`** — the only class that touches the signing key directly:

```java
@Service
public class JwtService {

    private final SecretKey key;
    private final long expirationMs;

    public JwtService(@Value("${jwt.secret}") String secret, @Value("${jwt.expiration-ms}") long expirationMs) {
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.expirationMs = expirationMs;
    }

    public String generateToken(String email) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(email)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusMillis(expirationMs)))
                .signWith(key)
                .compact();
    }

    public String extractEmail(String token) {
        try {
            return parseClaims(token).getSubject();
        } catch (JwtException | IllegalArgumentException e) {
            return null;
        }
    }

    public boolean isValid(String token, String expectedEmail) {
        String email = extractEmail(token);
        return email != null && email.equals(expectedEmail);
    }

    private Claims parseClaims(String token) {
        return Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
    }
}
```

A JWT has three base64url-encoded parts separated by dots: a header (algorithm info), a payload ("claims" — here just `sub` for the user's email, `iat` for issued-at, `exp` for expiry), and a signature computed over the first two using our secret key. `signWith(key)` produces that signature; anyone with the same key can verify a token wasn't tampered with, but *cannot* forge a new one without knowing the key — that's the entire security property a JWT provides.

`extractEmail` deliberately swallows every `JwtException` (a malformed token, a bad signature, or an **expired** one — `jjwt`'s `parseSignedClaims` checks the `exp` claim internally and throws `ExpiredJwtException`, a subtype) and returns `null` instead of propagating. This means "missing," "tampered with," and "expired" all funnel through the exact same code path downstream — the filter below just checks for `null` and treats anything non-`null` as valid, without needing to know or care *why* a bad token failed.

**`JwtAuthenticationFilter.java`** — runs once per incoming request, ahead of Spring Security's own authentication machinery:

```java
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String authHeader = request.getHeader("Authorization");

        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            filterChain.doFilter(request, response);
            return;
        }

        String token = authHeader.substring(7);
        String email = jwtService.extractEmail(token);

        if (email != null && SecurityContextHolder.getContext().getAuthentication() == null) {
            UserDetails userDetails = userDetailsService.loadUserByUsername(email);

            if (jwtService.isValid(token, userDetails.getUsername())) {
                var authToken = new UsernamePasswordAuthenticationToken(
                        userDetails, null, userDetails.getAuthorities());
                authToken.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                SecurityContextHolder.getContext().setAuthentication(authToken);
            }
        }

        filterChain.doFilter(request, response);
    }
}
```

`OncePerRequestFilter` is a Spring base class guaranteeing this logic runs exactly once per request even if the servlet container internally forwards/includes it multiple times (which, as Bug #1's `/error` forward showed, genuinely happens). The logic: no `Authorization: Bearer ...` header → do nothing, just pass the request along unauthenticated (the authorization rules further down the chain decide whether that's acceptable for this particular path). A header present → extract the email, look the user up, and if the token is valid for that exact user, build an `Authentication` object and stash it in the `SecurityContextHolder` — a thread-local Spring Security consults for the rest of the request's lifetime to answer "who is making this request, and are they allowed to do what they're trying to do."

---

## `SecurityConfig.java` — wiring everything into one filter chain

```java
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public AuthenticationManager authenticationManager(PasswordEncoder passwordEncoder) {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider(userDetailsService);
        provider.setPasswordEncoder(passwordEncoder);
        return new ProviderManager(provider);
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED))
                )
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/auth/**", "/actuator/health", "/actuator/info", "/error").permitAll()
                        .anyRequest().authenticated()
                )
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }
}
```

Reading it piece by piece:

- **`passwordEncoder()` — `BCryptPasswordEncoder`.** BCrypt is a deliberately slow, salted hashing algorithm built for passwords (unlike SHA-256, which is *fast*, making it a poor choice — fast hashes are exactly what makes brute-forcing feasible). Every hash embeds its own random salt, so two users with the same password get completely different stored hashes, and the encoder auto-detects the salt when checking a password later. One consequence baked into `RegisterRequest`'s validation: BCrypt only actually uses the first 72 bytes of its input, silently ignoring anything beyond that — so the password field is capped at 72 characters, turning a silent truncation footgun into a clear validation error.
- **`authenticationManager()`.** This is the bean `AuthService.login()` (below) delegates to. A `DaoAuthenticationProvider` is Spring Security's standard "look the user up via `UserDetailsService`, then compare the submitted password against the stored hash via `PasswordEncoder`" implementation — we just wire our two beans into it and wrap it in a `ProviderManager` (the standard `AuthenticationManager` implementation, supporting a *list* of providers, though we only need one).
- **`.csrf(csrf -> csrf.disable())`.** CSRF (Cross-Site Request Forgery) attacks work by exploiting the fact that browsers *automatically* attach cookies to requests, even cross-site ones — so a malicious page can trigger a state-changing request that rides on a victim's already-logged-in session cookie. We authenticate with a `Authorization: Bearer <token>` header instead of a cookie, and a malicious page has no way to read or forge that header on the victim's behalf (unlike a cookie, which the browser attaches for you). No ambient credential, no CSRF exposure — the protection genuinely doesn't apply to this design, so disabling it isn't cutting a corner.
- **`.sessionManagement(... STATELESS)`.** No server-side session is created or consulted, ever — every request must carry its own valid token. This is what `DESIGN.md` §2 means by a horizontally-scalable backend: any instance behind a load balancer can validate any request without needing to share session state with any other instance.
- **`.exceptionHandling(...)` — the 401-vs-403 fix.** By default, Spring Security's stateless setups fall back to returning `403 Forbidden` for *any* denied request, whether the caller sent no credentials at all or sent valid credentials that just aren't allowed to do the thing they're asking. Those are different situations with a real HTTP-semantics distinction: `401 Unauthorized` means "you haven't proven who you are"; `403 Forbidden` means "I know who you are, and the answer is no." Explicitly wiring an `HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)` makes "no token" or "bad token" correctly return 401 — this was caught and fixed during end-to-end curl testing (see the verification log below), not planned upfront.
- **`.authorizeHttpRequests(...)`.** The actual access-control rule list: `/api/auth/**` (register/login must be reachable *without* being logged in already — that would be a chicken-and-egg problem), the two actuator endpoints M0 already exposed, and `/error` (see Bug #1's second half) are the only unauthenticated paths. Literally everything else requires a valid, non-expired token belonging to a real user.
- **`.addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)`.** Slots `JwtAuthenticationFilter` into the chain ahead of Spring Security's own username/password form-login filter (which we never actually use, but which is still part of the default chain), so our token check runs early enough to populate the `SecurityContext` before anything downstream needs to consult it.

---

## `com.jobpulse.auth` DTOs, service, and controller

**Request/response shapes** — three small `record`s:

```java
public record RegisterRequest(
        @NotBlank @Email String email,
        @NotBlank @Size(min = 8, max = 72, message = "password must be between 8 and 72 characters") String password,
        @NotBlank String fullName
) {}

public record LoginRequest(@NotBlank @Email String email, @NotBlank String password) {}

public record AuthResponse(String token, String tokenType, String email, String fullName) {
    public AuthResponse(String token, String email, String fullName) {
        this(token, "Bearer", email, fullName);
    }
}
```

The `jakarta.validation` annotations (`@NotBlank`, `@Email`, `@Size`) are declarative request validation — Spring MVC checks them automatically before the controller method body even runs, as long as the parameter is annotated `@Valid` (see `AuthController` below). `AuthResponse`'s second, smaller constructor is a convenience overload that fills in the conventional `"Bearer"` token-type string, so callers (like `AuthService`) don't have to repeat that literal everywhere a token gets returned.

**`AuthService.java`** — the actual business logic:

```java
public AuthResponse register(RegisterRequest request) {
    if (userRepository.existsByEmail(request.email())) {
        throw new EmailAlreadyInUseException(request.email());
    }

    User user = new User();
    user.setEmail(request.email());
    user.setPasswordHash(passwordEncoder.encode(request.password()));
    user.setFullName(request.fullName());
    userRepository.save(user);

    String token = jwtService.generateToken(user.getEmail());
    return new AuthResponse(token, user.getEmail(), user.getFullName());
}

public AuthResponse login(LoginRequest request) {
    authenticationManager.authenticate(
            new UsernamePasswordAuthenticationToken(request.email(), request.password())
    );

    User user = userRepository.findByEmail(request.email())
            .orElseThrow(() -> new IllegalStateException("Authenticated user vanished: " + request.email()));

    String token = jwtService.generateToken(user.getEmail());
    return new AuthResponse(token, user.getEmail(), user.getFullName());
}
```

`register` hashes the plaintext password immediately — `passwordEncoder.encode(...)` — and the plaintext value never gets stored, logged, or held onto a moment longer than necessary. It returns a token straight away, so a newly registered user is immediately logged in without a second round-trip to `/login`.

`login` deliberately doesn't hand-roll its own password comparison. It hands the raw credentials to `authenticationManager.authenticate(...)` — the same `AuthenticationManager` bean `SecurityConfig` built — which internally calls our `UserDetailsServiceImpl` to look the user up and our `PasswordEncoder` to check the password. If the password is wrong, that call throws `BadCredentialsException` on its own; we don't check a boolean and throw manually. This keeps the "is this password right" logic in exactly one place instead of two subtly different implementations (one for login, one implicitly inside Spring Security's filter chain for other flows).

**`EmailAlreadyInUseException.java`** is a small marker exception with no special behavior — its only job is to be a distinct type `GlobalExceptionHandler` can catch and map to a specific HTTP status.

**`AuthController.java`** ties the HTTP layer together:

```java
@PostMapping("/register")
public ResponseEntity<AuthResponse> register(@Valid @RequestBody RegisterRequest request) {
    return ResponseEntity.status(HttpStatus.CREATED).body(authService.register(request));
}

@PostMapping("/login")
public ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginRequest request) {
    return ResponseEntity.ok(authService.login(request));
}
```

`register` returns `201 Created` (a new resource — the user account — now exists), `login` returns `200 OK` (nothing new was created, we're just handing back a token for an existing account). `@Valid` is what actually triggers the Bean Validation annotations on `RegisterRequest`/`LoginRequest` from above.

---

## `com.jobpulse.common` — turning exceptions into real HTTP responses

```java
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(EmailAlreadyInUseException.class)
    public ResponseEntity<ErrorResponse> handleEmailAlreadyInUse(EmailAlreadyInUseException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(new ErrorResponse(ex.getMessage()));
    }

    @ExceptionHandler(BadCredentialsException.class)
    public ResponseEntity<ErrorResponse> handleBadCredentials(BadCredentialsException ex) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(new ErrorResponse("Invalid email or password"));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .collect(Collectors.joining(", "));
        return ResponseEntity.badRequest().body(new ErrorResponse(message));
    }
}
```

`@RestControllerAdvice` is Spring MVC's mechanism for centralizing exception handling across *every* controller in the app, instead of wrapping each controller method in its own try/catch. Without this class, all three of these situations (duplicate email, wrong password, invalid registration data) would have surfaced to the client as a generic, unhelpful `500 Internal Server Error` — this file is exactly what turns those into the meaningful `409`, `401`, and `400` responses a real API consumer needs. `ErrorResponse` is a one-field `record` (`message`) — the consistent shape every error from this API returns in, so a frontend can always look for `error.message` regardless of which endpoint failed.

---

## End-to-end verification

Every claim above was checked against a running instance — Postgres in Docker, the backend started with `./mvnw spring-boot:run` — via `curl`, not just "it compiled":

| Request | Expected | Got |
|---|---|---|
| `POST /api/auth/register` (new email) | `201` + JWT | ✅ |
| `POST /api/auth/register` (same email again) | `409`, clear message | ✅ |
| `POST /api/auth/login` (correct password) | `200` + JWT | ✅ |
| `POST /api/auth/login` (wrong password) | `401`, clear message | ✅ |
| `GET /api/users/me` (no `Authorization` header) | `401` | ✅ (after the entry-point fix — was `403` before) |
| `GET /api/users/me` (valid token) | `200` + user's email/fullName | ✅ |
| `GET /api/users/me` (garbage token) | `401` | ✅ |
| `POST /api/auth/register` (invalid email, 5-char password) | `400`, both field errors listed | ✅ |
| `./mvnw test` (full `@SpringBootTest` context, now including Security + Flyway + JPA) | passes | ✅ |

That last row matters more than it looks: M0's "smoke test" (`contextLoads()`, an empty test body) still does real work — it boots the *entire* application context, including the new `SecurityFilterChain`, `JwtAuthenticationFilter`, `AuthenticationManager`, and the Flyway migration against the real database. If any bean here were miswired, this test would fail with no code of its own needed to catch it.

---

## What's next

M2 (Applications CRUD) is next per the roadmap: the `job_applications` entity, repository, DTOs, service, and controller, scoped to the logged-in user (every query filtered by `user_id`, per `DESIGN.md` §6's ownership rule). The pattern established here — Flyway migration → entity → repository → DTOs → service → controller → curl-verified end to end — carries forward unchanged.

# MEMORY.md — Task by Task (Backend API)
Cross-session project memory. Max ~50 lines: summarize or prune outdated info that no longer adds value.

## Current State
- **Phase:** **MVP Backend Complete** — `005-hardening` completed (`HRD-REQ-01`–`HRD-REQ-23`, `EC-01`–`EC-08`) on top of `004-notifications`, `003-tasks-and-tags`, `002-groups`, `001-auth-and-users`, and `000-foundation`. All 6 vertical modules + production hardening + OpenAPI 3 contract + full E2E MVP journey verified (**263 tests passing**, 0 failures/errors/skipped, Spotless clean).
- **Requirements Baseline:** Vision (`docs/TaskByTask-SVD.md` v1.0) and SRS (`docs/TaskByTask-SRS.md` v1.0) are frozen, strictly separating **MVP** (individual groups, tasks, tags, filters, Keyset pagination, browser/in-app due notifications) from **Posterior** (shared groups, roles, subtasks, reminders, Brevo email, Analyst metrics, SSE).
- **Persistence:** PostgreSQL on Supabase (`public` schema only) managed via immutable Flyway `V1__init_schema.sql` and JPA `ddl-auto=validate`, with bounded HikariCP (`max=10`, `minIdle=2`).

## Learnings & Pitfalls to Avoid
- **Supabase + Flyway:** Always use port `5432` (Session Mode) for Flyway advisory locks; keep `ddl-auto=validate` and never run automated tests against remote Supabase or H2 (always `Testcontainers` `postgres:alpine`).
- **Date-Only vs. Date-Time Tasks (`RF-14`, `RF-28`):** Do not collapse optional due time into `00:00`. Date-only tasks notify at `09:00` local time (`ZoneId`) and expire at `23:59:59.999` local time.
- **Web Push Cleanup (`RF-02`, `RF-27`):** Remove device subscription on logout and auto-delete subscriptions returning HTTP `404`/`410`.
- **Spring Boot 4 + `db-scheduler` Test Isolation:** `NotificationSchedulerConfig` bridges `DbSchedulerAutoConfiguration` when `db-scheduler.enabled=true`. Keep `db-scheduler.enabled: false` in `application-test.yml` and `@DirtiesContext(AFTER_CLASS)` on scheduler/E2E tests.
- **Spring Security Test `.with(csrf())` Caveat:** Calling `.with(csrf())` mutates the shared `CsrfFilter`'s repository to `TestCsrfTokenRepository(HttpSessionCsrfTokenRepository)`. Tests verifying real `CookieCsrfTokenRepository` (`GET /api/v1/auth/csrf`) must restore the bean via `WebTestUtils.setCsrfTokenRepository(...)`.

## Decisions (and Why)
- **Package-by-Feature + Pure Java 21:** Cohesive vertical modules (`auth`, `user`, `group`, `task`, `tag`, `notification`, `shared`) with immutable `record` DTOs, no Lombok/MapStruct.
- **Spring Session JDBC + Unified Cookies + Bootstrap CSRF:** 7-day stateful revocable `SESSION` (`HttpOnly`) + `XSRF-TOKEN` share `SecurityProperties.Cookie` (`SameSite=Lax` default, optional `Domain`, `Secure` forced in `prod` or derived from `request.isSecure()` in `dev`/`test`). Public `GET /api/v1/auth/csrf` bootstraps SPAs while keeping CSRF active on `/auth/{register,login,recover-password}`.
- **Trusted Proxy Boundary (`HRD-REQ-03`, `EC-02`):** `TrustedProxyResolver` (pure IPv4/IPv6 CIDR, zero DNS lookups) + `TrustedForwardedHeaderFilter` (`HIGHEST_PRECEDENCE`, `server.forward-headers-strategy: none`) + `RateLimitService` strip/ignore spoofed `X-Forwarded-*`/`X-Real-IP` from untrusted public IPs.
- **Auth & Crypto:** `DelegatingPasswordEncoder` (`{bcrypt}` default) with transparent `upgradeEncoding` re-hashing on login; in-memory `Bucket4j` + `Caffeine` rate limiting (`RNF-06`).
- **Jackson 3 Strictness & RFC 7807 Edge Errors:** `JacksonConfig` registers `JsonMapperBuilderCustomizer` (`CoercionAction.Fail` on Textual/Boolean/Integer/DateTime, `FAIL_ON_UNKNOWN_PROPERTIES=false`), entrypoints use Jackson 3 `ObjectMapper`, and `GlobalExceptionHandler` covers `400`/`404`/`405+Allow`/`406`/`415`/`500` in Spanish `application/problem+json`.
- **Tasks, Tags & Notifications:** `PatchField<T>` + `UpdateTaskRequestDeserializer` for PATCH; native `INSERT ... ON CONFLICT` for race-free tags & push subscriptions; RFC 8291/8292 Web Push via BouncyCastle + `HttpClient`; `DueNotificationJobService` TX1 -> push -> TX2 + 30-day retention job.
- **OpenAPI 3 & Prod Profile:** `OpenApiConfig` (`springdoc` `3.2.0`) exposes Spanish docs, `sessionCookie`/`csrfToken` schemes, `ProblemDetailResponse`, and flat `UpdateTaskPatchRequest` (hiding `PatchField*`). `application-prod.yml` disables Swagger by default (`404` RFC 7807), locks Actuator to `/actuator/health{,/liveness,/readiness}` (`show-details: never`), enables 25s graceful shutdown, and documents all env vars in `.env.example`.

## Next Steps
- [x] Complete MVP backend specs `000-foundation` through `005-hardening` (263/263 tests green).
- [ ] Ready for frontend integration / production deployment or Posterior phase planning (`RF-08`–`RF-11`, `RF-17`–`RF-21`, `RF-23`, `RF-26`, `RF-30`–`RF-33`).

# MEMORY.md — Task by Task (Backend API)
Cross-session project memory. Max ~50 lines: summarize or prune outdated info that no longer adds value. 
*Note for AI Agent: The sections below are an initial baseline; feel free to add, merge, or remove sections as needed as the project evolves.*

## Current State
- **Phase:** Foundation completed (`000-foundation`). Base architecture, Flyway schema (`V1__init_schema.sql`), Testcontainers base, RFC 7807 global exception handling in Spanish, Keyset pagination codec, RateLimitService (Bucket4j+Caffeine), DateTimeProvider (09:00 AM & overdue rules), and Spring Security with JDBC sessions and CSRF are implemented and verified (75 tests passing).
- **Requirements Baseline:** Vision (`docs/TaskByTask-SVD.md` v1.0) and Software Requirements (`docs/TaskByTask-SRS.md` v1.0) are complete and frozen, separating **MVP** (individual groups, tasks, tags, basic filters, browser due-date notifications) from **Posterior** (shared groups up to 5 members, roles, subtasks, fixed reminders, Brevo email API, Analyst metrics, SSE real-time updates).
- **Persistence:** PostgreSQL hosted on Supabase (used strictly as a database, `public` schema only) managed via Flyway and Spring Data JPA (`ddl-auto=validate`).

## Learnings & Pitfalls to Avoid
- **Supabase + Flyway:** Always use port `5432` (Session Mode) for Flyway migrations so PostgreSQL advisory locks work; keep `spring.jpa.hibernate.ddl-auto=validate` and never run automated tests against remote Supabase or H2 (always use `Testcontainers` `postgres:alpine`).
- **Date-Only vs. Date-Time Tasks (`RF-14`, `RF-28`):** Do not collapse optional due time into `00:00`. Date-only tasks notify at `09:00` local time (`ZoneId`) and expire at `23:59:59.999` local time.
- **Web Push Cleanup (`RF-02`, `RF-27`):** Stop sending push notifications to a device upon logout and auto-delete push subscriptions returning HTTP `404`/`410`.

## Decisions (and Why)
- **Package-by-Feature + Pure Java 21:** Chosen over layered/Lombok setups to keep domain modules (`auth`, `user`, `group`, `task`, `tag`, `notification`, `shared`) cohesive, avoid annotation-processor friction with JPA, and use immutable `record` DTOs.
- **Spring Session JDBC + `HttpOnly` Cookies + CSRF:** Provides 7-day stateful revocable sessions (`RF-02`) on existing PostgreSQL at $0 cost, avoiding Redis overhead for the 1,000-user / 100-concurrent target (`RNF-02`), while remaining trivial to migrate to Spring Session Redis later.
- **`DelegatingPasswordEncoder` + `UserDetailsPasswordService`:** Uses `{bcrypt}` by default for passwords and 8 single-use recovery codes (`RF-01`, `RF-03`), enabling zero-downtime transparent re-hashing to `{argon2}` on login.
- **In-Memory `Bucket4j` + `Caffeine`:** Protects login/auth endpoints from brute force (`RNF-06`) with bounded memory (`maximumSize` + TTL) and zero database IOPS cost.
- **`db-scheduler` + VAPID Web Push (BouncyCastle):** Lightweight single-table PostgreSQL scheduler that supports deterministic `reschedule`/`cancel` by task ID when due dates or completion states change (`RF-13`, `RF-14`, `RF-28`).
- **Posterior Integrations (Brevo HTTP API + SSE):** HTTP API avoids free-tier hosting SMTP port blocks (`RF-11`); `SseEmitter` provides lightweight unidirectional real-time updates (`RF-33`) without WebSocket/STOMP complexity.

## Next Steps
- [x] Create and execute `docs/specs/000-foundation/` (`spec.md`, `plan.md`, `tasks.md`): configure `pom.xml` dependencies, Spotless, Flyway baseline (`V1__init_schema.sql` with RNF-20 extensible tables), `Testcontainers` base class, RFC 7807 `GlobalExceptionHandler`, Keyset pagination codec, RateLimitService, DateTimeProvider, and base `SecurityFilterChain`.
- [ ] Draft and implement `docs/specs/001-auth-and-users/` (`RF-01` to `RF-05`: signup with `"Personal"` group + 8 recovery codes, session login/logout, profile/timezone preferences, account deletion).
- [ ] Draft and implement `docs/specs/002-groups/`, `docs/specs/003-tasks-and-tags/`, and `docs/specs/004-notifications/` to complete the MVP.

# MEMORY.md — Task by Task (Backend API)
Cross-session project memory. Max ~50 lines: summarize or prune outdated info that no longer adds value. 
*Note for AI Agent: The sections below are an initial baseline; feel free to add, merge, or remove sections as needed as the project evolves.*

## Current State
- **Phase:** `003-tasks-and-tags` completed (`RF-12`–`RF-19`, `RF-21`–`RF-23`, `RF-25`, `RF-28`) on top of `002-groups`, `001-auth-and-users`, `000-foundation`, and Swagger/dev fixes (`springdoc` `3.2.0`, `docker-compose.dev.yml`). Implemented group-scoped tag catalog & auto-creation, task CRUD, explicit `PATCH` partial updates, completion toggling, deletion, tag detaching, move/copy across groups, and Keyset pagination with composite ordering/filtering; full suite passes (**180 tests passing**, 0 failures/skipped).
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
- **Tasks & Tags (`003-tasks-and-tags`):**
  - `PatchField<T>` + Jackson 3 `UpdateTaskRequestDeserializer` distinguish omitted vs `null` vs value cleanly, while `StrictLocalDateDeserializer`/`StrictLocalTimeDeserializer` reject empty/blank date/time strings with `400`.
  - Tag resolution uses native `INSERT ... ON CONFLICT (group_id, TRIM(LOWER(name))) DO NOTHING RETURNING id` so concurrent tag creation is race-free.
  - `TaskDueSchedulePort` (`JdbcTaskDueScheduleAdapter`) isolates `scheduled_tasks` writes cleanly ahead of `004-notifications`.
  - `TaskQueryRepositoryImpl` builds dynamic JPQL Keyset seek predicates matching `ORDER BY completed ASC, dueDate ASC NULLS LAST, dueTime ASC NULLS LAST, createdAt ASC, id ASC`, truncating persisted timestamps to `ChronoUnit.MICROS` for PostgreSQL `TIMESTAMPTZ` cursor parity.

## Next Steps
- [x] Create and execute `docs/specs/000-foundation/`, `001-auth-and-users/`, `002-groups/`, and `003-tasks-and-tags/`.
- [ ] Draft and implement `docs/specs/004-notifications/` (Web Push VAPID + `db-scheduler` due-date jobs) and `docs/specs/005-hardening/` to complete the MVP.

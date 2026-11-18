# MEMORY.md — Task by Task (Backend API)
Cross-session project memory. Max ~50 lines: summarize or prune outdated info that no longer adds value. 
*Note for AI Agent: The sections below are an initial baseline; feel free to add, merge, or remove sections as needed as the project evolves.*

## Current State
- **Phase:** `004-notifications` completed (`RF-27`–`RF-29`, `FND-REQ-08`, `FND-REQ-30`, `FND-REQ-31`, `FND-REQ-34`, `FND-REQ-35`, `RNF-20`) on top of `003-tasks-and-tags` (`RF-12`–`RF-16`, `RF-22`, `RF-24`, `RF-25`, `RF-28`), `002-groups` (`RF-06`, `RF-07`), `001-auth-and-users` (`RF-01`–`RF-05`), `000-foundation`, and Swagger/dev fixes (`springdoc` `3.2.0`, `docker-compose.dev.yml`). All MVP functional backend specs (`000`–`004`) are implemented and verified (**211 tests passing**, 0 failures/errors/skipped).
- **Requirements Baseline:** Vision (`docs/TaskByTask-SVD.md` v1.0) and Software Requirements (`docs/TaskByTask-SRS.md` v1.0) are complete and frozen, separating **MVP** (individual groups, tasks, tags, basic filters, browser due-date notifications, notification center) from **Posterior** (shared groups up to 5 members, roles, subtasks, fixed reminders, Brevo email API, Analyst metrics, SSE real-time updates).
- **Persistence:** PostgreSQL hosted on Supabase (used strictly as a database, `public` schema only) managed via Flyway (`V1__init_schema.sql`) and Spring Data JPA (`ddl-auto=validate`).

## Learnings & Pitfalls to Avoid
- **Supabase + Flyway:** Always use port `5432` (Session Mode) for Flyway migrations so PostgreSQL advisory locks work; keep `spring.jpa.hibernate.ddl-auto=validate` and never run automated tests against remote Supabase or H2 (always use `Testcontainers` `postgres:alpine`).
- **Date-Only vs. Date-Time Tasks (`RF-14`, `RF-28`):** Do not collapse optional due time into `00:00`. Date-only tasks notify at `09:00` local time (`ZoneId`) and expire at `23:59:59.999` local time.
- **Web Push Cleanup (`RF-02`, `RF-27`):** Stop sending push notifications to a device upon logout and auto-delete push subscriptions returning HTTP `404`/`410`.
- **Spring Boot 4 + `db-scheduler` Test Isolation:** `db-scheduler-spring-boot-starter` `15.0.0` targets Spring Boot 3's `DataSourceAutoConfiguration` package, so `NotificationSchedulerConfig` bridges `DbSchedulerAutoConfiguration` directly when `db-scheduler.enabled=true`. Keep `db-scheduler.enabled: false` in `application-test.yml` (and `@DirtiesContext(AFTER_CLASS)` on `NotificationSchedulerIntegrationTest`) so background scheduler threads never consume `scheduled_tasks` rows from mocked-`Clock` tests.

## Decisions (and Why)
- **Package-by-Feature + Pure Java 21:** Chosen over layered/Lombok setups to keep domain modules (`auth`, `user`, `group`, `task`, `tag`, `notification`, `shared`) cohesive with acyclic dependencies (`auth -> notification -> task, group, user, shared`) and immutable `record` DTOs.
- **Spring Session JDBC + `HttpOnly` Cookies + CSRF:** Provides 7-day stateful revocable sessions (`RF-02`) on existing PostgreSQL at $0 cost, avoiding Redis overhead for the 1,000-user / 100-concurrent target (`RNF-02`).
- **`DelegatingPasswordEncoder` + `UserDetailsPasswordService`:** Uses `{bcrypt}` by default for passwords and 8 single-use recovery codes (`RF-01`, `RF-03`), enabling zero-downtime transparent re-hashing to `{argon2}` on login.
- **In-Memory `Bucket4j` + `Caffeine`:** Protects login/auth endpoints from brute force (`RNF-06`) with bounded memory (`maximumSize` + TTL) and zero database IOPS cost.
- **Tasks & Tags (`003-tasks-and-tags`):**
  - `PatchField<T>` + Jackson 3 `UpdateTaskRequestDeserializer` distinguish omitted vs `null` vs value cleanly, while `StrictLocalDateDeserializer`/`StrictLocalTimeDeserializer` reject empty/blank date/time strings with `400`.
  - Tag resolution uses native `INSERT ... ON CONFLICT (group_id, TRIM(LOWER(name))) DO NOTHING RETURNING id` so concurrent tag creation is race-free.
  - `TaskDueSchedulePort` (`JdbcTaskDueScheduleAdapter`) writes `("task-due-notification", "task-due-{taskId}")` directly to `scheduled_tasks`.
  - `TaskQueryRepositoryImpl` builds dynamic JPQL Keyset seek predicates matching `ORDER BY completed ASC, dueDate ASC NULLS LAST, dueTime ASC NULLS LAST, createdAt ASC, id ASC` with `ChronoUnit.MICROS` timestamp parity.
- **Notifications & Web Push (`004-notifications`):**
  - `VapidConfigurationState` validates P-256 curve points and `d * G == Q` keypair matching at startup with safe degradation (`enabled: false, publicKey: null`) when disabled or misconfigured.
  - `PushSubscriptionService` uses native PostgreSQL `INSERT ... ON CONFLICT (endpoint) DO UPDATE ... RETURNING ..., (xmax = 0) AS inserted` for atomic `201`/`200` registration and shared-browser user rebinding.
  - `WebPushEncryptionService` implements RFC 8291 (`aes128gcm` ECDH + HKDF-SHA256 + 86B RFC 8188 header) and RFC 8292 (`ES256` 64B IEEE P1363 JWT via local `BouncyCastleProvider`) over `java.net.http.HttpClient`.
  - `DueNotificationJobService` + `CustomTask<Void>` isolate TX1 (task check, `NTF-REQ-13` reschedule detection, `RNF-20` recipient resolution, localized `Notification` commit) -> non-transactional HTTP Web Push -> TX2 (`404`/`410` subscription cleanup), plus a daily UTC `RecurringTask` purging notifications older than 30 days.

## Next Steps
- [x] Create and execute `docs/specs/000-foundation/`, `001-auth-and-users/`, `002-groups/`, `003-tasks-and-tags/`, and `004-notifications/`.
- [ ] Draft and implement `docs/specs/005-hardening/` (end-to-end production hardening, OpenAPI polish, and final MVP verification).

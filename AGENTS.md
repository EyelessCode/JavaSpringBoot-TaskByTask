# AGENTS.md — Task by Task (Backend API)
RESTful backend API for **Task by Task**, a Spanish-language, Mobile-First web platform that helps users organize pending tasks across groups and tags, filter work, receive browser due-date notifications, and (in later phases) collaborate in shared groups with role-based permissions.

## Stack & Structure
- **Tech Stack:** Java 21, Spring Boot 4.1.2, Maven Wrapper (`./mvnw`), PostgreSQL (Supabase `public` schema), Spring Data JPA + Specifications, Flyway, Spring Security + Spring Session JDBC (`HttpOnly` cookie + CSRF), Bucket4j + Caffeine Cache, `db-scheduler` (Kagkarlsson), BouncyCastle Web Push (VAPID RFC 8292), Jakarta Validation + RFC 7807 `ProblemDetail`, `springdoc-openapi`, Spring Boot Actuator, JUnit 5 + Testcontainers (`postgres:alpine`) + Awaitility, Spotless.
- **Folders:** Package-by-Feature architecture under `com.eyelesscode.task_by_task`:
  - `src/main/java/com/eyelesscode/task_by_task/`: Vertical feature packages (`auth/`, `user/`, `group/`, `task/`, `tag/`, `notification/`, `shared/`).
  - `src/main/resources/db/migration/`: Immutable Flyway SQL migrations (`V1__*.sql`) targeting PostgreSQL `public` schema.
  - `src/test/java/com/eyelesscode/task_by_task/`: Unit and `@SpringBootTest` + Testcontainers integration tests mirroring feature packages.
  - `docs/`: Master product documents (`TaskByTask-SVD.md`, `TaskByTask-SRS.md`, `constitution.md`).
  - `docs/specs/NNN-spec-name/`: SDD feature folders containing `spec.md` (requirements), `plan.md` (technical design), and `tasks.md` (atomic checklist).

## Commands
- Install deps: `./mvnw clean test-compile`
- Run local dev: `./mvnw spring-boot:run -Dspring-boot.run.profiles=dev`
- Run tests: `./mvnw test` (or `./mvnw test -Dtest=ClassNameTest` for targeted runs)
- Typecheck / Lint: `./mvnw spotless:check compile test-compile` (use `./mvnw spotless:apply` to auto-format)

## Conventions
- **Style:** Pure Java 21 (no Lombok, no MapStruct), `record` for all request/response DTOs, constructor injection only (never field `@Autowired`), explicit manual mappers, and Keyset (Seek) pagination for unbounded lists (`RNF-04`).
- **Language:** Source code, SQL identifiers, and REST endpoints in **English**; all user-facing error messages (`ProblemDetail.title`/`detail`), notifications, and emails in **Spanish** (`RNF-14`).
- **Reference File:** Follow the cross-cutting patterns in `src/main/java/com/eyelesscode/task_by_task/shared/` (from `000-foundation`) and the canonical vertical slice in `src/main/java/com/eyelesscode/task_by_task/task/`, aligned with `docs/TaskByTask-SRS.md`.

## Domain Rules & Known Pitfalls
- **Watch out (Dates, Timezones & 09:00 AM Rule — RF-12, RF-14, RF-28):** Persist timestamps in UTC and user timezone as an IANA `ZoneId`. Tasks may have no due date, a date-only (`LocalDate`), or date+time. Date-only tasks trigger notifications at **09:00 AM user local time** (`RF-28`) but only become visually overdue at **end-of-day (`23:59:59.999`) user local time** (`RF-14`). Past due dates are valid on creation (born overdue). Always reschedule/cancel `db-scheduler` jobs (`task-due-{taskId}`) when due date, completion state, or deletion changes.
- **Watch out (MVP Extensibility & "Personal" Group — RF-06, RF-07, RNF-20):** Even in the single-user MVP, design DB tables with group memberships/roles and nullable `assigned_user_id` on tasks to avoid destructive migrations in Posterior phases. The default `"Personal"` group (`is_personal = true`) is created at signup and can be renamed, but must **never** be deleted or converted into a shared group.
- **Watch out (Group-Scoped Tags & Task Ordering — RF-15, RF-16, RF-22):** Tags belong to the **group** (`group_id`), never the user, with case/whitespace-insensitive uniqueness (`TRIM(LOWER(name))`). Removing a tag from a task must distinguish between detaching from the task vs. deleting from the entire group. Default task order is fixed (`completed ASC` in "all" filter, `due_date ASC NULLS LAST`, `created_at ASC`, `id ASC`); Keyset cursors must match this exact composite order.
- **Watch out (Supabase & Flyway Connection):** Run Flyway exclusively against Supabase **Session Mode (port `5432`)** or direct connection (never Transaction Pooler port `6543` due to PostgreSQL advisory locks), keep `spring.jpa.hibernate.ddl-auto=validate`, and never touch internal Supabase schemas (`auth`, `storage`, `realtime`).

## Way of Working (SDD & Memory)
- Read `docs/constitution.md`, `MEMORY.md`, and the active spec folder (`docs/specs/NNN-spec-name/`) before writing code.
- Follow the strict SDD flow inside each feature folder: `spec.md` (what/why) -> `plan.md` (how/architecture) -> `tasks.md` (atomic execution).
- Work in small, atomic tasks following `tasks.md`; mark tasks as completed (`[x]`) as you progress and never make massive unrequested refactors.
- After completing a session or milestone, update `MEMORY.md` with new decisions, learnings, or status changes.

## Boundaries
- ✅ **Always:** Validate all Appendix B limits and permissions in the backend (`RNF-07`) returning Spanish `ProblemDetail` (RFC 7807) responses; hash passwords and the 8 single-use recovery codes via `PasswordEncoderFactories.createDelegatingPasswordEncoder()`; run Spotless and tests before finishing.
- ⚠️ **Ask First:** Adding or updating dependencies/plugins in `pom.xml`, creating new Flyway SQL migrations, changing global security/CORS/CSRF configuration, deleting files, or editing `docs/TaskByTask-SVD.md` and `docs/TaskByTask-SRS.md`.
- 🚫 **Never:** Hardcode Supabase credentials, VAPID keys, or Brevo API keys; modify already-applied Flyway migrations; use H2 or remote cloud Supabase for automated tests; or implement Posterior/out-of-scope features (shared groups, subtasks, priorities, custom colors) during MVP tasks.

## Verification
- Run `./mvnw spotless:apply` followed by `./mvnw spotless:check compile test-compile` and ensure zero formatting or compilation errors.
- Run `./mvnw test` and verify all unit and `@SpringBootTest` + `Testcontainers` integration tests pass (confirming Flyway migrations, JPA `ddl-auto=validate`, security/CSRF rules, and RFC 7807 `ProblemDetail` error contracts).

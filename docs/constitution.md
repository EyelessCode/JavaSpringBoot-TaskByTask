# Project Constitution — Task by Task (Backend API)

This constitution defines the 6 non-negotiable architectural and Spec-Driven Development (SDD) principles for the Task by Task backend.

1. **Spec-First & SRS Traceability:** Never write production code without an active spec folder (`docs/specs/NNN-spec-name/` containing `spec.md`, `plan.md`, and `tasks.md`) explicitly traced to `RF-XX` and `RNF-XX` requirements in `docs/TaskByTask-SRS.md`.
2. **Strict Phase Scope & Extensible Schema (RNF-20):** Implement only active-phase (MVP) requirements and never build Posterior or excluded features prematurely, while designing PostgreSQL tables (`public` schema via Flyway) to support future shared-group memberships and task assignments without destructive migrations.
3. **Package-by-Feature Modularity & Pure Java 21:** Enforce strict vertical slices (`auth`, `user`, `group`, `task`, `tag`, `notification`, `shared`) using pure Java 21 (`record` DTOs, constructor injection, zero Lombok/MapStruct annotations).
4. **Backend Authority & RFC 7807 Localization:** Validate all business rules, timezones (UTC storage, user IANA `ZoneId` for the 09:00 AM alert and end-of-day overdue rules), permissions, and Appendix B limits in the backend (`RNF-07`), returning Spanish user-facing errors exclusively as `ProblemDetail` (RFC 7807).
5. **Defense-in-Depth & Zero-Cost Architecture:** Enforce stateful `Spring Session JDBC` (`HttpOnly` cookies + CSRF), `DelegatingPasswordEncoder` (`{bcrypt}` default, upgrade-ready via `UserDetailsPasswordService`), in-memory `Bucket4j` + `Caffeine` rate limiting, `db-scheduler` on PostgreSQL, and zero hardcoded secrets.
6. **Test-Verified Atomic Delivery:** Execute tasks atomically from `tasks.md` and never mark a task `[x]` until `./mvnw spotless:check compile test-compile` and `./mvnw test` (backed by real PostgreSQL via `Testcontainers` and `ddl-auto=validate`) pass with zero errors.

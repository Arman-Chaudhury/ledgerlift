# ledgerlift — build plan

Target application: IBM Application Developer Intern – Strategy & Transformation 2027
(IBM Consulting, New York). The posting screens for SQL/PLSQL, integration
patterns (REST/SOAP), data-migration tools (FBDI/HDL), reporting tools
(OTBI/BI Publisher), Java application frameworks, containers, unit testing.
The portfolio covered none of the Java/SQL/migration/reporting cluster;
ledgerlift fills it in one repo.

## What it is

An FBDI-style (File-Based Data Import) bulk migration and integration
service for General Ledger journals, modelled on the Oracle Fusion
`GL_INTERFACE` flow:

    template CSV/ZIP -> staging (interface) table -> validation rules
    -> error-correction CSV -> transactional posting to ledger tables
    -> reports (data model + template, BI-Publisher style)

Exposed through REST (OpenAPI) and SOAP (WSDL, Spring-WS). Runs on H2 for
dev/tests and PostgreSQL for production; the posting step exists twice —
a Java/JDBC path (portable) and a PL/pgSQL stored procedure — and CI
proves both leave the ledger in the identical state.

## Non-goals

No UI beyond HTML reports. No Oracle database (PL/pgSQL stands in for
PL/SQL; the README says so plainly). No auth beyond an API key header.

## Stack

Java 17 (release), Spring Boot 3.3, Spring MVC, Spring JDBC, Flyway, H2,
PostgreSQL, Spring-WS (SOAP), springdoc-openapi, Thymeleaf (HTML reports),
JUnit 5 + AssertJ + MockMvc, Maven wrapper, GitHub Actions, Docker.

## Milestones (one commit each, all on PR #1)

1. **Scaffold** — Maven wrapper, Spring Boot app, Flyway V1 schema
   (ledgers, accounts, periods, import_batches, gl_interface staging,
   import_errors, journal_headers, journal_lines), H2 dev profile,
   `postgres` profile, actuator health, CI workflow (unit job).
2. **Template parser** — FBDI-style CSV template (`GlInterfaceRow`),
   header-row detection, ZIP support, strict/lenient policies, line-level
   parse errors kept (never silently dropped). Unit tests on fixtures.
3. **Staging load** — `POST /api/v1/imports` (multipart) creates a batch,
   checksum idempotency (same file twice -> same batch), rows into
   `gl_interface` in JDBC batches; `GET /api/v1/imports/{id}`.
4. **Validation engine** — `ValidationRule` interface; set-based SQL rules
   (unknown account, closed/missing period, unknown currency, unbalanced
   journal, duplicate line) + Java row rules; results in `import_errors`;
   `GET /api/v1/imports/{id}/errors.csv` error-correction file that
   round-trips (fix, re-upload, clean).
5. **Posting** — Java path (`JdbcPostingService`) in one transaction,
   batch status machine (LOADED -> VALIDATED -> POSTED / REJECTED);
   PL/pgSQL `ll_post_batch(batch_id)` in a postgres-only migration;
   `PostingEquivalenceIT` (Postgres profile, CI service container) posts
   the same fixture both ways and diffs the ledger tables.
6. **Reports** — data-model layer (SQL) + template layer (Thymeleaf HTML,
   CSV, JSON): trial balance, batch summary, error summary, account
   activity; `GET /api/v1/reports/{name}?format=`.
7. **Integration surfaces** — SOAP endpoint (`ImportStatus`, `SubmitImport`
   by base64) with generated WSDL at `/ws/imports.wsdl`; OpenAPI at
   `/v3/api-docs`; API-key filter; contract tests for both.
8. **Ship** — multi-stage Dockerfile, `docker-compose.yml` with Postgres,
   `demo.sh` reproducing README numbers, README, CI green, tag v0.1.0.

## Verifiable claims (for the resume)

Every number on the resume must come from `./demo.sh` or the test suite.

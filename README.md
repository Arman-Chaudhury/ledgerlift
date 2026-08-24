# ledgerlift

[![ci](https://github.com/Arman-Chaudhury/ledgerlift/actions/workflows/ci.yml/badge.svg)](https://github.com/Arman-Chaudhury/ledgerlift/actions/workflows/ci.yml)

FBDI-style bulk data migration and integration service for General Ledger
journals, in Java 17 / Spring Boot. Modelled on the Oracle Fusion
`GL_INTERFACE` flow: a CSV (or ZIP) template is staged into an interface
table, validated by a set-based SQL rule pack, returned as an error-correction
file when it fails, and posted to the ledger in one transaction when it
passes — then reported on through a data-model/layout split in the style of
BI Publisher. Exposed over REST (OpenAPI) and SOAP (WSDL).

```
template CSV/ZIP ──> gl_interface (staging) ──> 14 validation rule codes ──> errors.csv (re-importable)
                                             └──> journal_headers / journal_lines ──> reports (json | csv | html)
```

Posting is implemented twice on purpose — a portable JDBC path and a
PL/pgSQL stored procedure — and CI proves on a real PostgreSQL that the two
leave the ledger byte-for-byte identical.

## Quick start

```bash
./mvnw test            # 37 tests on in-memory H2 (~15 s)
./demo.sh              # builds the jar, starts on H2, walks the whole flow, stops
docker compose up      # PostgreSQL + app; posting runs through the stored procedure
```

`./demo.sh` output (abridged):

```
== 1. upload the Q2 journals template (426 lines, 144 journals)
batch 1: LOADED, 426 rows staged
== 2. re-upload the same bytes -> idempotent (same batch id, no new rows)
== 3. validate                 -> VALIDATED (0 errors, 0 warnings)
== 4. post (engine: java)      -> POSTED via java: 144 journals, 426 lines
== 5. a file with business errors -> REJECTED (15 errors, 3 warnings); rule codes:
   ACCOUNT_DISABLED, DATE_OUTSIDE_PERIOD, DR_AND_CR, DUPLICATE_LINE, FOREIGN_CURRENCY,
   MIXED_LEDGERS, PERIOD_CLOSED, REQUIRED_FIELD, UNBALANCED_JOURNAL, UNKNOWN_ACCOUNT,
   UNKNOWN_CURRENCY, UNKNOWN_LEDGER, UNKNOWN_PERIOD
== 6. reports: trial balance nets to 0.00 across 18 accounts
== 7. SOAP: <status>POSTED</status> <rowCount>426</rowCount>
```

## The flow

| Step | Endpoint | What happens |
|---|---|---|
| Upload | `POST /api/v1/imports` (multipart `file`, `policy=STRICT\|LENIENT`) | SHA-256 of the bytes is the batch identity: the same file twice returns the same batch (200, not 201) and stages nothing new. The template is parsed (header row optional, BOM/CRLF/quoted commas handled, first `.csv` inside a ZIP) and every line lands in `gl_interface` via JDBC batch inserts. Parse problems are recorded per line and column, never dropped; `STRICT` rejects the file, `LENIENT` stages it with the bad cells nulled so business validation can report them alongside. |
| Validate | `POST /api/v1/imports/{id}/validate` | The rule pack runs as a handful of set-based SQL statements (one per rule, not one per row). Findings are `ERROR` or `WARNING`; zero errors → `VALIDATED`, otherwise `REJECTED`. Re-runnable: business findings are replaced, parse findings kept. |
| Correct | `GET /api/v1/imports/{id}/errors.csv` | Only the failing lines, in template column order, plus an `ERRORS` column listing every finding on that line; batch-level findings (unbalanced journals, mixed ledgers) trail as `# BATCH` comments. The parser recognises the extra column, so the corrected file re-imports as-is. |
| Post | `POST /api/v1/imports/{id}/post?engine=java\|procedure` | Only `VALIDATED` batches; never twice. Three `INSERT … SELECT` statements create one `journal_headers` row per journal with totals, resolve every line to a real `accounts.id` / `periods.id`, and refuse to commit if the posted line count differs from the staged count (stale validation). |
| Report | `GET /api/v1/reports/{name}?format=json\|csv\|html&…` | `trial-balance` (optional `period`), `batch-summary`, `error-summary?batchId=`, `account-activity?account=` (running balance). One data model per report; the layout is chosen by `format`. |
| SOAP | `POST /ws`, WSDL at `/ws/imports.wsdl` | `submitImportRequest` (base64 content, optional validate-on-submit) and `getImportStatusRequest`, contract-first from [`imports.xsd`](src/main/resources/imports.xsd). Domain errors surface as `Client` SOAP faults. |

All `/api/**` calls and SOAP operations need `X-API-Key` (`LEDGERLIFT_API_KEY`,
default `dev-key`); the WSDL, `/v3/api-docs`, `/swagger-ui.html` and
`/actuator/health` stay open.

## Validation rule codes

| Code | Level | Meaning |
|---|---|---|
| `REQUIRED_FIELD` | line | ledger, period, journal, account, currency, date, or an amount is missing |
| `DR_AND_CR` | line | a line carries both a debit and a credit |
| `UNKNOWN_LEDGER` / `MIXED_LEDGERS` | line / batch | ledger not defined / file names more than one ledger |
| `UNKNOWN_ACCOUNT` / `ACCOUNT_DISABLED` | line | not in the chart of accounts / disabled |
| `UNKNOWN_PERIOD` / `PERIOD_CLOSED` / `DATE_OUTSIDE_PERIOD` | line | period problems |
| `UNKNOWN_CURRENCY` | line | currency not enabled |
| `FOREIGN_CURRENCY` | line, warning | line currency differs from the ledger's (posted at entered amounts) |
| `UNBALANCED_JOURNAL` / `MIXED_CURRENCY_JOURNAL` | batch | per-journal debits ≠ credits / more than one currency |
| `DUPLICATE_LINE` | line, warning | identical journal/account/reference/amount seen earlier in the file |
| `PARSE` | line | recorded at upload: bad number, bad date, negative amount, >2 decimals, wrong column count |

Adding a rule is one `@Bean ValidationRule` in [`SqlRules.java`](src/main/java/io/ledgerlift/validation/SqlRules.java).

## Two posting engines, one ledger

[`JdbcPostingEngine`](src/main/java/io/ledgerlift/posting/JdbcPostingEngine.java)
runs on any database; [`ll_post_batch`](src/main/resources/db/migration/postgres/V100__post_batch_procedure.sql)
is the same logic as a PL/pgSQL procedure (status guard, row-count
reconciliation, `RAISE EXCEPTION` on mismatch). `ledgerlift.posting.engine`
picks the default; the `postgres` profile defaults to `procedure`.
[`PostingEquivalenceIT`](src/test/java/io/ledgerlift/posting/PostingEquivalenceIT.java)
posts the same fixtures through both and asserts `journal_headers` and
`journal_lines` are identical column for column, and that the procedure
refuses a batch that is not `VALIDATED` even when called directly.

PL/pgSQL stands in for PL/SQL here: the schema and rule SQL are portable, the
procedure is PostgreSQL-specific, and there is no Oracle database in the loop.

## Tests and CI

- `./mvnw test` — 37 tests on H2: parser (fixtures for every parse error),
  staging (idempotency, lenient linking), every validation rule on a
  fixture that triggers each one, the error-correction round trip (fix the
  file, re-upload, clean), posting invariants, report data models and all
  three layouts, and full-container contract tests for SOAP/WSDL/OpenAPI/auth.
- CI ([`ci.yml`](.github/workflows/ci.yml)) runs that suite on H2, then the
  whole suite plus the equivalence ITs against a PostgreSQL 16 service container.

## Layout

```
src/main/java/io/ledgerlift/
  template/    CSV/ZIP template parser (RFC 4180 reader, policies, parse errors)
  imports/     batches, staging repository, ImportService (checksum idempotency)
  validation/  ValidationRule, SqlRules (the pack), ValidationService, ErrorCorrectionFile
  posting/     PostingEngine, JdbcPostingEngine, ProcedurePostingEngine, PostingService
  reports/     ReportDefinition catalogue, ReportData, ReportRenderer (csv/html)
  soap/        WsConfig (WSDL from imports.xsd), ImportsEndpoint (DOM-based)
  security/    ApiKeyFilter
  web/         REST controllers, ApiExceptionHandler
src/main/resources/db/migration/common/    V1 schema, V2 demo reference data (portable)
src/main/resources/db/migration/postgres/  V100 ll_post_batch procedure
examples/     the template files demo.sh uploads
```

## Why this exists

Built in August 2026 as the portfolio piece for an IBM Consulting application
developer internship whose posting asks for SQL/PL-SQL, REST/SOAP integration,
FBDI/HDL-style data migration, BI-Publisher-style reporting and Java
frameworks — the one cluster the rest of my portfolio (Go, Python, React)
did not cover. See [BUILD_PLAN.md](BUILD_PLAN.md) for the milestone plan.

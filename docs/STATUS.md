# Project status

Project memory. Read it before starting work and when resuming a session after a
context compaction. Update it when closing a phase and when taking an
architectural decision.

Last updated: 2026-10-03

## Current phase

**Phase 1 — Core, in progress.** The development stack, the schema and the
tenant isolation mechanism are in place. Authentication, the documents API and
the ingestion worker are still to do.

Done:

- Docker Compose stack with Postgres (pgvector) and Ollama, plus a provisioning
  script that creates the two database roles.
- Flyway migrations: extensions, core tables, Row Level Security policies.
- `TenantContext` and `TenantAwareDataSource`, which carry the tenant onto every
  connection.
- Testcontainers base class that runs the real provisioning script, and the
  tenant isolation test suite.
- Access tokens: signing configuration, issuer, role enum. Verified by unit
  tests covering expiry, a wrong secret and a foreign issuer.

The suite is green: 52 tests, in CI as well as locally.

Next, in order:

1. Finish authentication: password hashing, the security filter chain, the login
   endpoint, and wiring `TenantContextFilter` into the chain after the bearer
   token filter so it binds the tenant from a verified token. The filter is
   written but deliberately not committed until the chain exists, because its
   ordering relative to authentication is what makes it correct.
2. Collections and documents API, with upload and content hashing.
3. Ingestion worker claiming jobs with `FOR UPDATE SKIP LOCKED`.
4. The `rag` service: chunking and embeddings over the two demo corpora.

## Phase plan

1. **Core.** JWT auth and roles, tenants with Row Level Security, document
   upload and registration, ingestion worker, embedding generation. Closing it
   gives an end-to-end flow: a PDF is uploaded and ends up indexed.
2. **RAG.** Hybrid retrieval, grounded generation with citations, SSE streaming,
   and the chat frontend.
3. **Quality.** Evaluation dataset, recall and faithfulness metrics, CI, and a
   README with an architecture diagram.

## Decisions taken

| Decision | Rationale | Rejected |
|---|---|---|
| Multi-tenancy via `tenant_id` + Row Level Security | Isolation is enforced by the database engine, not by a `where` clause someone can forget | Schema or database per tenant: more isolation than needed at a much higher operational cost |
| Ingestion queue on a PostgreSQL table with `FOR UPDATE SKIP LOCKED` | Solid pattern that avoids one more container and keeps everything transactional | RabbitMQ and Kafka: disproportionate for this project's volume |
| Hybrid retrieval, vector + `tsvector`, fused with Reciprocal Rank Fusion | Clear improvement over vector-only for acronyms and proper nouns, and both indexes live in the same database, so the tenant filter stays inside the SQL | Vector-only search: worse recall, to be measured in phase 3 |
| LLM provider behind an interface we own | Allows developing against Ollama at no cost and evaluating with the Claude API | Coupling to a single provider |
| JWT issued by the application itself | Enough for the scope and avoids running another service | Keycloak: configuration left ready in case Entra ID is plugged in later |
| Demo corpus: Spring Boot PDF and FastAPI Markdown | Two formats and two different structures, from public and recognizable documentation | A synthetic corpus: less convincing in a demo |
| Spring Boot 4.1.1 on Java 25 | The current stable release, and Initializr no longer offers the 3.x line. Java 25 matches the JDK installed on the development machine | Java 21: would mean compiling for an older target than the available runtime for no benefit |
| Two database roles: owner for Flyway, unprivileged role for the service | Row Level Security is bypassed by superusers and by table owners, so the service must be neither | A single role: would silently disable the isolation the policies are there to provide |
| `FORCE ROW LEVEL SECURITY` on every policy-bearing table | Keeps isolation even if the service is ever misconfigured to connect as the owner | Plain `ENABLE`: relies entirely on connecting with the right role |
| Composite `(tenant_id, id)` foreign keys between tenant-scoped tables | Makes a cross-tenant reference impossible in the database, not just unlikely in code | Plain foreign keys on the parent id: a bug could attach a document to another tenant's collection |
| Tenant carried on the connection by a `DataSource` decorator, set on every checkout | Setting it at checkout rather than only clearing it at return is what prevents a pooled connection from being reused with a previous tenant attached | A transaction-scoped `SET LOCAL` via an aspect: leaves work outside transactions unscoped |
| `tenant` table excluded from Row Level Security | Login has to resolve a tenant from its slug before any tenant context can exist. The table holds no customer data and is never exposed as a listing | Applying a policy to it too: creates a chicken-and-egg problem at login |
| Test configuration lives in `application-test.yaml` under a `test` profile | A file named `application.yaml` in test resources shadows the main one instead of layering on it, silently dropping every setting it does not repeat | Overriding in a test-scoped `application.yaml`: cost an hour to diagnose once |
| Isolation tests assert on SQLSTATE 42501, not on the error text | The wording of a Postgres message is not a contract; the SQLSTATE is | Matching the message only: broke as soon as Spring wrapped the driver exception |
| The ingestion worker visits tenants one at a time instead of being granted cross-tenant access | Row Level Security hides the queue from an unbound worker, which is correct. Binding each tenant in turn keeps the invariant that no statement the application makes can see across the boundary | A `BYPASSRLS` role, which would void the project's central claim; a `SECURITY DEFINER` function, which `FORCE ROW LEVEL SECURITY` defeats anyway. Cost is a query per tenant per sweep; past a few hundred tenants, keep a small unscoped table of which tenants have work |
| A claim commits `RUNNING` before the work starts, and a reaper returns abandoned jobs | Committing first is what stops a second worker taking the row, since the `SKIP LOCKED` lock ends with the transaction. The price is that a crashed worker leaves the row owned by nobody, which the reaper undoes | One transaction around claim and work: a failure would roll back the attempt counter too, so the job would retry forever without backing off |
| Testcontainers mounts the real provisioning script | The isolation tests then exercise the same roles, grants and default privileges as production, instead of a test-only approximation | Creating the role inline in test setup: would prove less and could drift from the script |

## Out of scope for the first version

Message broker, Keycloak, S3/MinIO storage, quotas and rate limiting, semantic
caching, OpenTelemetry, admin panel, Kubernetes and Terraform, OCR, external
connectors, and cross-encoder reranking. These go in the README as possible
improvements.

## Local development notes

- Docker Desktop must be running before the tests: the suite starts a real
  Postgres through Testcontainers and fails fast without a daemon.
- `cp .env.example .env` and fill it in before `docker compose up -d`. The
  provisioning script only runs on an empty data directory, so changing
  `APP_DB_USER` or `APP_DB_PASSWORD` later needs the volume removed
  (`docker compose down -v`).

## Known rough edges

- `updated_at` is maintained both by a database trigger and by Hibernate's
  `@UpdateTimestamp`. The trigger wins, so behaviour is correct, but there are
  two mechanisms where one would do. Worth collapsing onto `@Generated` so the
  database is the single source of truth and the entity still reads back fresh.
- Chunks carry no embedding yet. Keyword retrieval works on them already; the
  vector half arrives with the rag service.
- PDF uploads are accepted but nothing can read them yet, so they will exhaust
  their retries and fail. The PDF extractor is the next piece.

# Glosa

Multi-tenant retrieval over your own documents. An organisation uploads its
documentation, its people ask questions in plain language, and every answer is
built only from those documents and cited back to the page it came from.

**[How to run it and what to try](docs/USING.md)** · **[What the evaluation
found](docs/EVALUATION.md)** · **[Decisions and why](docs/STATUS.md)**

Upload a PDF or a Markdown file, wait a few seconds while it is read and
indexed, then ask a question. The answer streams back with the passages it was
built from, each pointing at the page or section it came from, so you can check
it rather than trust it.

## Why it is built this way

The interesting problem here is not calling a language model. It is that several
organisations share one database, and a single missing `where` clause would show
one of them another's documents. So isolation is not left to application code:

- Every tenant-scoped table is under **PostgreSQL Row Level Security**. The
  policies compare each row against the tenant carried on the connection, so a
  query that forgets to filter returns **nothing** rather than someone else's
  rows.
- The service connects as an **unprivileged database role**. Row Level Security
  is bypassed by superusers and by table owners, so a single shared role would
  silently switch the whole mechanism off. Flyway uses the owner role for
  migrations; the application never does.
- The tenant reaches the connection through a `DataSource` decorator that sets it
  on **every checkout**, not just clears it on return. That ordering is what makes
  connection pooling safe: a connection cannot be handed out still carrying the
  previous borrower's tenant.
- The tenant comes from a **signed claim** in the access token, never from a
  header, a path segment or a request body.
- Child rows reference their parent through a **composite `(tenant_id, id)`
  foreign key**, so a document cannot be attached to another tenant's collection
  even if application code tries.

The test suite proves these rather than asserting them in prose: its queries omit
the tenant filter on purpose, and one test pins the connection pool to a single
connection, which is the strictest arrangement for catching a leaked context.

## Architecture

```
            React  ──  chat with citations, document upload
              │
      ┌───────▼────────┐  auth and roles, tenants, documents,
      │  core  (Java)  │  ingestion queue, REST API
      │  Spring Boot 4 │
      └───┬────────┬───┘
          │        │
          │   ┌────▼──────────┐  chunking, embeddings,
          │   │  rag (Python) │  hybrid retrieval, grounded answers
          │   │    FastAPI    │
          │   └────┬──────────┘
          │        │
      ┌───▼────────▼───┐  relational data, vectors (pgvector),
      │   PostgreSQL   │  and keyword search (tsvector)
      └────────────────┘
```

Java carries the API, identity and the transactional work. Python handles the
parts where the machine learning ecosystem lives. One database holds both halves
of retrieval, which is what keeps the tenant filter inside the same query as the
ranking.

| Component | Stack | Responsibility |
|---|---|---|
| `core/` | Java 25, Spring Boot 4 | Auth and roles, tenants with RLS, documents, ingestion queue, REST API |
| `rag/` | Python, FastAPI | Embeddings and grounded generation, through a local model |
| `web/` | React, TypeScript | Streaming chat with citations, document upload |
| `eval/` | Python | Measures retrieval and grounding against the running stack |

## Design decisions worth a word

**Hybrid retrieval — and the measurement that says it is not winning.** Vector
similarity (pgvector, HNSW, cosine) and keyword search (`tsvector`, GIN) run
against the same tables and are merged by rank with Reciprocal Rank Fusion,
because the two scores are not comparable and weighing them directly means
inventing a conversion.

On this corpus hybrid does **not** beat semantic alone: 0.91 against 0.93 at
rank 1. That is in [the evaluation](docs/EVALUATION.md) rather than quietly
omitted, along with the two defects measuring it exposed — keyword search was
contributing nothing at all, and the fusion weight was not the lever it looked
like.

**The ingestion queue is a database table.** Workers claim jobs with
`SELECT ... FOR UPDATE SKIP LOCKED`, which gives at-most-one-worker-per-row
without running a broker. Retry backoff is a `run_after` column; idempotency is a
partial unique index that allows only one open job per document. A broker would
have been one more thing to operate for no gain at this size.

**The language model sits behind an interface we own.** Development runs against
a local model through Ollama at no cost; evaluation runs against a hosted one.
Neither choice leaks into the rest of the code.

**Answers must be refusable, and at first this one was not.** Refusal
correctness measured zero: every question the documents did not cover was
answered anyway, confidently, citing passages about something else. The cause
was structural rather than a weak model — vector search returns its nearest
neighbours however far away they are, so the generator never saw an empty result
and never had the chance to decline. Relevance is now decided in its own call,
before any answer is written, and a calibrated distance threshold drops passages
too far from the question to cite.

## Running it

Requirements: Docker, JDK 25.

```bash
cp .env.example .env
```

Fill in `.env` — every value, no defaults are shipped for secrets. Generate the
token signing key with `openssl rand -hex 32`. Then:

```bash
docker compose up -d
```

```bash
cd core && ./mvnw spring-boot:run
```

The API description is then at `http://localhost:8080/swagger-ui.html`, and the
raw specification at `/v3/api-docs`. Both are open without a token because the UI
needs the spec before you have one; turn them off in production with
`springdoc.api-docs.enabled=false`.

### Tests

```bash
cd core && ./mvnw test
```

The suite starts a real PostgreSQL through Testcontainers and runs the actual
provisioning script against it, so the roles, grants and default privileges under
test are the ones a deployment gets. Docker must be running.

## Not in the first version

Deliberately out of scope, to keep the thing finishable: message broker,
Keycloak, object storage, quotas and rate limiting, semantic caching,
OpenTelemetry, an admin panel, Kubernetes and Terraform, OCR, connectors to
Confluence or Drive, and cross-encoder reranking.

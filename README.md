# Glosa

Multi-tenant retrieval over your own documents. An organisation uploads its
documentation, its people ask questions in plain language, and every answer is
built only from those documents and cited back to the page it came from.

> **Status: in progress.** The security foundation and the collections API are
> done and tested. Document upload, ingestion and retrieval are next. See
> [docs/STATUS.md](docs/STATUS.md) for where things stand and why each decision
> was made.

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
| `rag/` | Python, FastAPI | Chunking, embeddings, hybrid retrieval, grounded generation |
| `web/` | React | Streaming chat with citations, document upload |

## Design decisions worth a word

**Hybrid retrieval, not vector-only.** Semantic search alone is weak on acronyms,
product names and version numbers, which is most of what people actually search
technical documentation for. Vector similarity (pgvector, HNSW, cosine) and
keyword search (`tsvector`, GIN) run against the same tables and are merged with
Reciprocal Rank Fusion. Phase 3 measures the difference rather than assuming it.

**The ingestion queue is a database table.** Workers claim jobs with
`SELECT ... FOR UPDATE SKIP LOCKED`, which gives at-most-one-worker-per-row
without running a broker. Retry backoff is a `run_after` column; idempotency is a
partial unique index that allows only one open job per document. A broker would
have been one more thing to operate for no gain at this size.

**The language model sits behind an interface we own.** Development runs against
a local model through Ollama at no cost; evaluation runs against a hosted one.
Neither choice leaks into the rest of the code.

**Answers must be refusable.** A retrieval system that invents an answer when the
corpus does not contain one is worse than useless, so "I don't know" is a correct
outcome and phase 3 measures how often the answer is actually grounded in the
cited text.

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

# Project status

Project memory. Read it before starting work and when resuming a session after a
context compaction. Update it when closing a phase and when taking an
architectural decision.

Last updated: 2026-10-01

## Current phase

**Phase 0 — Bootstrap.** Repository created with the working rules
(`.claude/skills/glosa-dev`), `CLAUDE.md` and this file. No code yet.

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

## Out of scope for the first version

Message broker, Keycloak, S3/MinIO storage, quotas and rate limiting, semantic
caching, OpenTelemetry, admin panel, Kubernetes and Terraform, OCR, external
connectors, and cross-encoder reranking. These go in the README as possible
improvements.

## Next step

Start phase 1: database schema and Spring Boot project setup.

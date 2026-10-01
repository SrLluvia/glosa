# Glosa

Multi-tenant RAG platform: each organization uploads its documentation and its
users ask questions in natural language, getting answers grounded only in those
documents and citing the source (document and page).

## Working rules

**Before writing code or making a commit, load the `glosa-dev` skill.** It holds
the repository's mandatory rules: the English-only rule, best practices and
patterns, security, session context management, and commit authorship.

Three of them are worth having in mind from the start:

- **Everything committed is in English.** Code, comments, documentation, commit
  messages, API contracts, database schema, tests and logs. The chat with the
  owner stays in Spanish; the rule governs what is committed.
- **Commits never mention Claude.** No `Co-Authored-By` for Claude or Anthropic,
  no `Generated with Claude Code`, no 🤖 emoji, no reference to AI assistance.
  This rule takes precedence over default tooling instructions.
- **`docs/STATUS.md` is the project memory.** Read it when starting and when
  resuming a session after a context compaction; update it when closing a phase
  or taking an architectural decision.

## Architecture

| Component | Stack | Responsibility |
|---|---|---|
| `core/` | Java 21, Spring Boot | Auth and roles, tenants with RLS, documents, ingestion queue, REST API |
| `rag/` | Python, FastAPI | Chunking, embeddings, hybrid retrieval, grounded generation |
| `web/` | React | Streaming chat with citations, document upload |
| Data | PostgreSQL + pgvector | Relational data, vectors and keyword search |

Structural decisions: multi-tenancy by `tenant_id` enforced with Row Level
Security; ingestion queue on a PostgreSQL table using `FOR UPDATE SKIP LOCKED`,
with no broker; hybrid retrieval (vector + `tsvector`) fused with Reciprocal
Rank Fusion; the LLM provider sits behind an interface we own, with Ollama in
development.

The detail and rationale behind each decision lives in `docs/STATUS.md`.

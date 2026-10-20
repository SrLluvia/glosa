# Using Glosa

How to get it running and try it. Five minutes, most of which is a download.

## What you need

- Docker
- JDK 25
- About 3 GB free, for the two models

## Start it

```bash
cp .env.example .env
```

Open `.env` and replace every value. For the token signing key:

```bash
openssl rand -hex 32
```

Then bring up the database, the model runner and the rag service:

```bash
docker compose up -d
```

Pull the two models. This is the slow step, and only happens once:

```bash
docker exec glosa-ollama ollama pull nomic-embed-text && docker exec glosa-ollama ollama pull llama3.2:3b
```

Build the web client into the service, and start it:

```bash
cd web && npm install && npm run build
```

```bash
cd core && ./mvnw spring-boot:run
```

Open **http://localhost:8080**.

## Try it

**Create an organisation.** Pick a handle, an email and a password of at least
twelve characters. You are its administrator.

**Make a collection** and **upload a document** — a PDF, a Markdown file or
plain text. Watch the status: it goes Queued, Reading, Indexing, Ready on its
own. A background worker picks it up within a couple of seconds.

**Ask something.** The sources appear first, then the answer streams in. Click a
source to read the passage it came from, and check the answer against it.

Things worth trying deliberately:

| Try this | What should happen |
|---|---|
| Ask something the document does not cover | It says so, instead of answering from the nearest passage |
| Upload a scanned PDF | It fails, and the message names OCR rather than saying "Failed" |
| Upload the same file twice | Refused, recognised by content rather than by filename |
| Create a second organisation and sign in as it | It sees none of the first one's documents |

## The API directly

The web client uses the same API you can. It is described at
**http://localhost:8080/swagger-ui.html**, with every endpoint runnable from the
page once you sign in through `POST /v1/auth/login` and paste the token into
**Authorize**.

```bash
curl -X POST http://localhost:8080/v1/auth/login -H 'Content-Type: application/json' -d '{"tenantSlug":"acme","email":"you@acme.test","password":"your-passphrase"}'
```

Search is worth poking at on its own, because it exposes how a result was found:

```bash
curl -G 'http://localhost:8080/v1/collections/<id>/search' --data-urlencode 'q=holiday leave' --data-urlencode 'mode=HYBRID' -H "Authorization: Bearer <token>"
```

`mode` accepts `KEYWORD`, `SEMANTIC` or `HYBRID`. Each result reports where each
half of the search ranked it, and how far the passage sat from the question.

## When something does not work

**Documents stay at Indexing.** The rag service cannot reach Ollama, or the
model is not pulled. Check `docker compose logs rag`.

**Answers never arrive.** The chat model is loading. The first question after a
restart can take a minute; afterwards it is seconds.

**Everything returns 401.** The token lasts an hour. Sign in again.

**The port is taken.** Something is already on 8080. On Windows, killing the
Maven wrapper does not kill the Java process it started:

```bash
powershell -Command "Get-NetTCPConnection -LocalPort 8080 -State Listen | ForEach-Object { Stop-Process -Id $_.OwningProcess -Force }"
```

## Running the tests

```bash
cd core && ./mvnw test
```

```bash
cd rag && pip install -e ".[dev]" && pytest -q
```

Both run in CI on every pull request. The Java suite starts a real PostgreSQL
through Testcontainers, so Docker has to be running.

## Measuring retrieval

With the stack up and documents ingested:

```bash
cd eval && python run_eval.py --compare
```

This registers a throwaway organisation, ingests a small corpus and scores
retrieval and answers. `docs/EVALUATION.md` has the numbers and what they mean.

"""Runs the evaluation against a live stack.

Deliberately goes through the HTTP API rather than reaching into the database.
What is measured should be what a user gets, including the retrieval limit, the
prompt and the fusion, not an idealised version reachable only from inside.
"""

from __future__ import annotations

import time
import uuid
from dataclasses import dataclass, field
from typing import Any

import httpx

from dataset import DOCUMENTS, QUESTIONS, Question

CORE_URL = "http://localhost:8080"
PASSWORD = "evaluation-run-passphrase"


@dataclass
class RetrievalOutcome:
    question: Question
    headings: list[str]
    """Headings of the retrieved passages, best first."""

    @property
    def rank_of_expected(self) -> int | None:
        """Position of the right passage, counting from one, or None if absent."""
        if self.question.expected_heading is None:
            return None
        for position, heading in enumerate(self.headings, start=1):
            if heading == self.question.expected_heading:
                return position
        return None


@dataclass
class AnswerOutcome:
    question: Question
    answer: str
    citation_count: int


@dataclass
class Report:
    mode: str
    retrieval: list[RetrievalOutcome] = field(default_factory=list)
    answers: list[AnswerOutcome] = field(default_factory=list)


class EvaluationClient:
    """Sets up a throwaway tenant and drives the API as a user would."""

    def __init__(self, base_url: str = CORE_URL) -> None:
        self._http = httpx.Client(base_url=base_url, timeout=300.0)
        self._token: str | None = None
        self.collection_id: str | None = None

    def __enter__(self) -> EvaluationClient:
        self._register()
        self._create_collection()
        self._upload_documents()
        return self

    def __exit__(self, *_: object) -> None:
        self._http.close()

    @property
    def _headers(self) -> dict[str, str]:
        return {"Authorization": f"Bearer {self._token}"}

    def _register(self) -> None:
        # A fresh tenant per run, so one evaluation never sees another's
        # documents and the figures cannot drift as runs accumulate.
        slug = f"eval-{uuid.uuid4().hex[:8]}"
        email = f"runner@{slug}.test"

        response = self._http.post("/v1/tenants", json={
            "slug": slug,
            "organisationName": "Evaluation run",
            "email": email,
            "password": PASSWORD,
        })
        response.raise_for_status()

        login = self._http.post("/v1/auth/login", json={
            "tenantSlug": slug, "email": email, "password": PASSWORD,
        })
        login.raise_for_status()
        self._token = login.json()["accessToken"]

    def _create_collection(self) -> None:
        response = self._http.post(
            "/v1/collections", json={"name": "Evaluation corpus"}, headers=self._headers
        )
        response.raise_for_status()
        self.collection_id = response.json()["id"]

    def _upload_documents(self) -> None:
        document_ids = []
        for filename, body in DOCUMENTS.items():
            response = self._http.post(
                f"/v1/collections/{self.collection_id}/documents",
                files={"file": (filename, body.encode("utf-8"), "text/markdown")},
                headers=self._headers,
            )
            response.raise_for_status()
            document_ids.append(response.json()["id"])

        self._wait_until_ready(document_ids)

    def _wait_until_ready(self, document_ids: list[str], timeout: float = 300.0) -> None:
        """Waits for ingestion, which embeds every passage and takes a while."""
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            states = []
            for document_id in document_ids:
                response = self._http.get(f"/v1/documents/{document_id}", headers=self._headers)
                response.raise_for_status()
                states.append(response.json()["status"])

            if all(state == "READY" for state in states):
                return
            if any(state == "FAILED" for state in states):
                raise RuntimeError(f"Ingestion failed: {states}")
            time.sleep(2)

        raise TimeoutError("Documents did not finish ingesting in time")

    def search(self, question: str, limit: int = 5, mode: str = "HYBRID") -> list[dict[str, Any]]:
        response = self._http.get(
            f"/v1/collections/{self.collection_id}/search",
            params={"q": question, "limit": limit, "mode": mode},
            headers=self._headers,
        )
        response.raise_for_status()
        return response.json()["passages"]

    def ask(self, question: str) -> tuple[str, int]:
        """Returns the answer text and how many passages it was given."""
        answer_parts: list[str] = []
        citations = 0

        with self._http.stream(
            "GET",
            f"/v1/collections/{self.collection_id}/ask",
            params={"q": question},
            headers={**self._headers, "Accept": "text/event-stream"},
        ) as response:
            response.raise_for_status()
            event = None
            for line in response.iter_lines():
                if line.startswith("event:"):
                    event = line[6:].strip()
                elif line.startswith("data:"):
                    # Only the single space the SSE format inserts is removed.
                    # Stripping further would weld the answer's words together,
                    # since tokens arrive with their own leading spaces.
                    payload = line[5:]
                    if payload.startswith(" "):
                        payload = payload[1:]
                    if event == "token":
                        answer_parts.append(payload)
                    elif event == "citations":
                        citations = payload.count('"number"')

        return "".join(answer_parts), citations

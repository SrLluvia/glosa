"""Client for the Ollama HTTP API.

The only place in this service that knows Ollama exists. Swapping in a hosted
provider means writing another class with the same two methods and changing the
dependency that builds it; nothing else needs to know.
"""

from __future__ import annotations

import logging
from collections.abc import AsyncIterator
from typing import Any

import httpx

from .settings import Settings

logger = logging.getLogger(__name__)


class ModelUnavailableError(RuntimeError):
    """The model is not installed, or Ollama cannot be reached.

    Kept distinct from a bad request so the API can answer 503 rather than 500:
    the caller did nothing wrong and retrying later may well work.
    """


class OllamaClient:
    """Embeddings and chat completions."""

    def __init__(self, client: httpx.AsyncClient, settings: Settings) -> None:
        self._client = client
        self._settings = settings

    async def embed(self, texts: list[str]) -> list[list[float]]:
        """Embeds several texts in one call.

        Batching matters: ingesting a document produces dozens of passages, and
        a round trip each would dominate the time spent.
        """
        payload = {"model": self._settings.embedding_model, "input": texts}
        data = await self._post("/api/embed", payload)

        embeddings = data.get("embeddings")
        if not isinstance(embeddings, list) or len(embeddings) != len(texts):
            raise ModelUnavailableError(
                f"Ollama returned {len(embeddings or [])} embeddings for {len(texts)} texts"
            )

        expected = self._settings.embedding_dimensions
        for vector in embeddings:
            if len(vector) != expected:
                # Storing these would corrupt the index silently: the column is
                # declared with a fixed width and similarity between vectors of
                # different models is meaningless anyway.
                raise ModelUnavailableError(
                    f"Model {self._settings.embedding_model} returned {len(vector)} "
                    f"dimensions, but the schema expects {expected}"
                )

        return embeddings

    async def stream_chat(self, system: str, user: str) -> AsyncIterator[str]:
        """Streams a completion token by token.

        Streaming is not decoration here. A grounded answer takes several
        seconds on a local model, and showing it as it arrives is the difference
        between the product feeling alive and feeling broken.
        """
        payload: dict[str, Any] = {
            "model": self._settings.chat_model,
            "messages": [
                {"role": "system", "content": system},
                {"role": "user", "content": user},
            ],
            "stream": True,
            "options": {
                # Low, because the job is to report what the passages say, not
                # to write something new.
                "temperature": 0.1,
            },
        }

        try:
            async with self._client.stream(
                "POST",
                f"{self._settings.ollama_url}/api/chat",
                json=payload,
                timeout=self._settings.request_timeout_seconds,
            ) as response:
                if response.status_code >= 400:
                    body = (await response.aread()).decode("utf-8", "replace")
                    raise ModelUnavailableError(self._describe(response.status_code, body))

                async for line in response.aiter_lines():
                    if not line.strip():
                        continue
                    chunk = _parse_json_line(line)
                    if chunk is None:
                        continue
                    content = chunk.get("message", {}).get("content", "")
                    if content:
                        yield content
                    if chunk.get("done"):
                        return
        except httpx.HTTPError as error:
            raise ModelUnavailableError(f"Could not reach Ollama: {error}") from error

    async def _post(self, path: str, payload: dict[str, Any]) -> dict[str, Any]:
        try:
            response = await self._client.post(
                f"{self._settings.ollama_url}{path}",
                json=payload,
                timeout=self._settings.request_timeout_seconds,
            )
        except httpx.HTTPError as error:
            raise ModelUnavailableError(f"Could not reach Ollama: {error}") from error

        if response.status_code >= 400:
            raise ModelUnavailableError(self._describe(response.status_code, response.text))

        return response.json()

    def _describe(self, status: int, body: str) -> str:
        if status == 404:
            # Much the commonest failure on a fresh machine, and the message
            # should say what to do about it rather than quote a status code.
            return (
                "Ollama does not have the model installed. Pull it with: "
                f"ollama pull {self._settings.embedding_model}"
            )
        return f"Ollama answered {status}: {body[:200]}"


def _parse_json_line(line: str) -> dict[str, Any] | None:
    """Parses one line of a streaming response, ignoring anything malformed."""
    import json

    try:
        parsed = json.loads(line)
    except ValueError:
        logger.warning("Ignoring an unparseable line from Ollama")
        return None
    return parsed if isinstance(parsed, dict) else None

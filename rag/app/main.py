"""HTTP surface of the rag service.

Deliberately small. This service holds no data, knows nothing about tenants and
has no database connection: it is a pure function over the text the core service
hands it. Tenant isolation is enforced in one place, and that place is the
database behind the core service, not here.
"""

from __future__ import annotations

import logging
from collections.abc import AsyncIterator
from contextlib import asynccontextmanager

import httpx
from fastapi import Depends, FastAPI, HTTPException, Request, status
from fastapi.responses import StreamingResponse
from pydantic import BaseModel, Field

from .ollama import ModelUnavailableError, OllamaClient
from .prompts import (
    GATE_SYSTEM_PROMPT,
    REFUSAL,
    SYSTEM_PROMPT,
    build_gate_prompt,
    build_user_prompt,
)
from .settings import Settings, get_settings

logger = logging.getLogger(__name__)


@asynccontextmanager
async def lifespan(app: FastAPI) -> AsyncIterator[None]:
    """One HTTP client for the process, reused across requests."""
    async with httpx.AsyncClient() as client:
        app.state.http_client = client
        yield


app = FastAPI(
    title="Glosa rag service",
    version="v1",
    summary="Embeddings and grounded answer generation",
    description=(
        "Internal service. Takes text, returns vectors or an answer built only "
        "from the passages it is given. Holds no data and enforces no access "
        "control, because it never sees whose text it is working on."
    ),
    lifespan=lifespan,
)


def get_client(request: Request, settings: Settings = Depends(get_settings)) -> OllamaClient:
    return OllamaClient(request.app.state.http_client, settings)


class EmbedRequest(BaseModel):
    texts: list[str] = Field(min_length=1, description="Texts to embed, in order")


class EmbedResponse(BaseModel):
    model: str
    dimensions: int
    embeddings: list[list[float]] = Field(description="One vector per input, in the same order")


class AnswerRequest(BaseModel):
    question: str = Field(min_length=1, max_length=2000)
    passages: list[str] = Field(
        min_length=1,
        description="Retrieved passages, most relevant first. Citations refer to their position.",
    )


@app.get("/health", summary="Liveness")
async def health() -> dict[str, str]:
    return {"status": "ok"}


@app.post("/v1/embed", response_model=EmbedResponse, summary="Embed texts")
async def embed(
    request: EmbedRequest,
    settings: Settings = Depends(get_settings),
    client: OllamaClient = Depends(get_client),
) -> EmbedResponse:
    _validate_texts(request.texts, settings)

    try:
        vectors = await client.embed(request.texts)
    except ModelUnavailableError as error:
        # 503, not 500: the caller did nothing wrong and a retry may succeed
        # once the model is pulled or Ollama is back.
        raise HTTPException(status.HTTP_503_SERVICE_UNAVAILABLE, str(error)) from error

    return EmbedResponse(
        model=settings.embedding_model,
        dimensions=settings.embedding_dimensions,
        embeddings=vectors,
    )


@app.post("/v1/answer", summary="Answer a question from passages")
async def answer(
    request: AnswerRequest,
    settings: Settings = Depends(get_settings),
    client: OllamaClient = Depends(get_client),
) -> StreamingResponse:
    """Streams an answer built only from the passages supplied.

    Plain text rather than server-sent events: the core service re-frames this
    for its own clients, and one encoding layer is enough.
    """
    _validate_texts(request.passages, settings)

    async def tokens() -> AsyncIterator[bytes]:
        try:
            # Decided before anything is written. A model asked to judge and
            # answer in one pass does both worse; this call has one job.
            verdict = await client.decide(
                GATE_SYSTEM_PROMPT, build_gate_prompt(request.question, request.passages)
            )
            if not _is_affirmative(verdict):
                logger.info("Declined to answer: the passages were judged insufficient")
                yield REFUSAL.encode("utf-8")
                return

            prompt = build_user_prompt(request.question, request.passages)
            async for piece in client.stream_chat(SYSTEM_PROMPT, prompt):
                yield piece.encode("utf-8")
        except ModelUnavailableError as error:
            # The response has already begun, so the status code is long since
            # sent. Saying so in the body beats a silent truncation that reads
            # like a complete answer.
            logger.error("Generation failed mid-stream: %s", error)
            yield b"\n\n[The answer could not be completed: the model became unavailable.]"

    return StreamingResponse(tokens(), media_type="text/plain; charset=utf-8")


def _is_affirmative(verdict: str) -> bool:
    """Reads the gate's answer.

    Anything that is not recognisably a yes counts as a no. A malformed verdict
    means the decision was not made, and the safe reading of "I could not tell"
    is to decline rather than to answer anyway.
    """
    return verdict.strip().upper().startswith("YES")


def _validate_texts(texts: list[str], settings: Settings) -> None:
    """Bounds the work one request can ask for."""
    if len(texts) > settings.max_texts_per_request:
        raise HTTPException(
            status.HTTP_422_UNPROCESSABLE_CONTENT,
            f"At most {settings.max_texts_per_request} texts per request",
        )
    for text in texts:
        if len(text) > settings.max_characters_per_text:
            raise HTTPException(
                status.HTTP_422_UNPROCESSABLE_CONTENT,
                f"Each text must be at most {settings.max_characters_per_text} characters",
            )
        if not text.strip():
            raise HTTPException(status.HTTP_422_UNPROCESSABLE_CONTENT, "Texts must not be blank")

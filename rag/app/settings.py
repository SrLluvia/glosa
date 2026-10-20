"""Configuration for the rag service."""

from functools import lru_cache

from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    """Settings, taken from the environment.

    No secrets live here. The service talks only to Ollama, which needs no
    credentials, and it never touches the database: the core service owns the
    data and this one is a pure function over the text it is handed.
    """

    model_config = SettingsConfigDict(env_prefix="GLOSA_RAG_", env_file=".env")

    ollama_url: str = "http://localhost:11434"

    # Produces 768-dimensional vectors, which is what chunk.embedding is
    # declared as. Changing this model means a migration on that column and a
    # re-embedding of everything already stored.
    embedding_model: str = "nomic-embed-text"
    embedding_dimensions: int = 768

    chat_model: str = "llama3.2:3b"

    # Generous, because a cold Ollama loads the model on the first call and a
    # small machine can take a while over it.
    request_timeout_seconds: float = 180.0

    # Bounded so one request cannot ask the service to embed a whole corpus in
    # a single call and hold a worker for minutes.
    max_texts_per_request: int = 64
    max_characters_per_text: int = 8000


@lru_cache
def get_settings() -> Settings:
    """Settings are read once; they do not change while the process runs."""
    return Settings()

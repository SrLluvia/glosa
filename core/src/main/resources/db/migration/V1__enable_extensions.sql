-- pgvector provides the vector type and the approximate nearest neighbour
-- indexes used by semantic retrieval. Keyword search needs no extension: the
-- built-in tsvector type and GIN indexes cover it.
CREATE EXTENSION IF NOT EXISTS vector;

-- Keeps updated_at honest without the application having to remember.
CREATE FUNCTION set_updated_at() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    NEW.updated_at := now();
    RETURN NEW;
END;
$$;

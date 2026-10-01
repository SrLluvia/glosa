-- Core schema.
--
-- Every tenant-scoped table carries tenant_id explicitly rather than relying on
-- a join to reach it. That keeps the Row Level Security policies added in V3
-- trivial to express and cheap to evaluate.
--
-- Child rows reference their parent through a composite (tenant_id, id) foreign
-- key. A plain foreign key on the parent id alone would let a row point at
-- another tenant's parent; the composite form makes that impossible in the
-- database, independently of application code.

CREATE TABLE tenant (
    id         uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    slug       text        NOT NULL,
    name       text        NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT tenant_slug_unique UNIQUE (slug),
    CONSTRAINT tenant_slug_format CHECK (slug ~ '^[a-z0-9][a-z0-9-]{0,38}$'),
    CONSTRAINT tenant_name_not_blank CHECK (btrim(name) <> '')
);

COMMENT ON TABLE tenant IS
    'Tenant registry. Deliberately not under Row Level Security: the login flow must resolve a tenant from its slug before any tenant context exists. Rows hold no customer data and the API never exposes a listing.';

CREATE TABLE app_user (
    id            uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id     uuid        NOT NULL REFERENCES tenant (id) ON DELETE CASCADE,
    email         text        NOT NULL,
    password_hash text        NOT NULL,
    role          text        NOT NULL,
    created_at    timestamptz NOT NULL DEFAULT now(),
    updated_at    timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT app_user_email_unique_per_tenant UNIQUE (tenant_id, email),
    CONSTRAINT app_user_email_lowercase CHECK (email = lower(email)),
    CONSTRAINT app_user_role_allowed CHECK (role IN ('ADMIN', 'EDITOR', 'VIEWER'))
);

CREATE TRIGGER app_user_set_updated_at
    BEFORE UPDATE ON app_user
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TABLE collection (
    id         uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id  uuid        NOT NULL REFERENCES tenant (id) ON DELETE CASCADE,
    name       text        NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT collection_name_unique_per_tenant UNIQUE (tenant_id, name),
    CONSTRAINT collection_name_not_blank CHECK (btrim(name) <> ''),
    -- Target of the composite foreign key from document.
    CONSTRAINT collection_tenant_scoped_id UNIQUE (tenant_id, id)
);

CREATE TABLE document (
    id             uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id      uuid        NOT NULL REFERENCES tenant (id) ON DELETE CASCADE,
    collection_id  uuid        NOT NULL,
    filename       text        NOT NULL,
    content_type   text        NOT NULL,
    byte_size      bigint      NOT NULL,
    -- SHA-256 of the uploaded bytes, used to reject duplicate uploads.
    content_hash   char(64)    NOT NULL,
    status         text        NOT NULL DEFAULT 'PENDING',
    page_count     integer,
    failure_reason text,
    created_at     timestamptz NOT NULL DEFAULT now(),
    updated_at     timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT document_collection_fk FOREIGN KEY (tenant_id, collection_id)
        REFERENCES collection (tenant_id, id) ON DELETE CASCADE,
    CONSTRAINT document_hash_unique_per_collection UNIQUE (collection_id, content_hash),
    CONSTRAINT document_byte_size_positive CHECK (byte_size > 0),
    CONSTRAINT document_page_count_positive CHECK (page_count IS NULL OR page_count > 0),
    CONSTRAINT document_status_allowed
        CHECK (status IN ('PENDING', 'PARSING', 'EMBEDDING', 'READY', 'FAILED')),
    -- A failure must say why, and any other status must not pretend to have one.
    CONSTRAINT document_failure_reason_matches_status
        CHECK ((status = 'FAILED') = (failure_reason IS NOT NULL)),
    CONSTRAINT document_tenant_scoped_id UNIQUE (tenant_id, id)
);

CREATE INDEX document_by_collection ON document (collection_id, created_at DESC);

CREATE TRIGGER document_set_updated_at
    BEFORE UPDATE ON document
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TABLE chunk (
    id          uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id   uuid        NOT NULL REFERENCES tenant (id) ON DELETE CASCADE,
    document_id uuid        NOT NULL,
    -- Position within the document, used to restore reading order.
    ordinal     integer     NOT NULL,
    page_number integer,
    heading     text,
    content     text        NOT NULL,
    token_count integer     NOT NULL,
    -- Dimension matches the embedding model configured for the rag service.
    embedding   vector(768),
    content_tsv tsvector    GENERATED ALWAYS AS (to_tsvector('english', content)) STORED,
    created_at  timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT chunk_document_fk FOREIGN KEY (tenant_id, document_id)
        REFERENCES document (tenant_id, id) ON DELETE CASCADE,
    CONSTRAINT chunk_ordinal_unique_per_document UNIQUE (document_id, ordinal),
    CONSTRAINT chunk_ordinal_non_negative CHECK (ordinal >= 0),
    CONSTRAINT chunk_page_number_positive CHECK (page_number IS NULL OR page_number > 0),
    CONSTRAINT chunk_token_count_positive CHECK (token_count > 0),
    CONSTRAINT chunk_content_not_blank CHECK (btrim(content) <> '')
);

-- Semantic half of hybrid retrieval. Cosine distance matches how the embedding
-- model is trained, so the index must be built for the same operator class.
CREATE INDEX chunk_embedding_hnsw ON chunk USING hnsw (embedding vector_cosine_ops);

-- Keyword half of hybrid retrieval.
CREATE INDEX chunk_content_tsv_gin ON chunk USING gin (content_tsv);

CREATE INDEX chunk_by_document ON chunk (document_id, ordinal);

CREATE TABLE ingestion_job (
    id           uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id    uuid        NOT NULL REFERENCES tenant (id) ON DELETE CASCADE,
    document_id  uuid        NOT NULL,
    state        text        NOT NULL DEFAULT 'QUEUED',
    attempts     integer     NOT NULL DEFAULT 0,
    max_attempts integer     NOT NULL DEFAULT 3,
    -- Claimed only once this moment has passed, which is how retry backoff is
    -- expressed without a scheduler.
    run_after    timestamptz NOT NULL DEFAULT now(),
    last_error   text,
    created_at   timestamptz NOT NULL DEFAULT now(),
    updated_at   timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ingestion_job_document_fk FOREIGN KEY (tenant_id, document_id)
        REFERENCES document (tenant_id, id) ON DELETE CASCADE,
    CONSTRAINT ingestion_job_state_allowed
        CHECK (state IN ('QUEUED', 'RUNNING', 'SUCCEEDED', 'FAILED')),
    CONSTRAINT ingestion_job_max_attempts_positive CHECK (max_attempts > 0),
    CONSTRAINT ingestion_job_attempts_bounded
        CHECK (attempts >= 0 AND attempts <= max_attempts)
);

COMMENT ON TABLE ingestion_job IS
    'Work queue held in the database. Workers claim rows with SELECT ... FOR UPDATE SKIP LOCKED, which gives at-most-one-worker-per-row without a broker.';

-- Supports the claim query, and stays small because finished jobs drop out.
CREATE INDEX ingestion_job_claimable ON ingestion_job (run_after)
    WHERE state = 'QUEUED';

-- Enqueueing the same document twice must not produce duplicate work.
CREATE UNIQUE INDEX ingestion_job_one_open_per_document ON ingestion_job (document_id)
    WHERE state IN ('QUEUED', 'RUNNING');

CREATE TRIGGER ingestion_job_set_updated_at
    BEFORE UPDATE ON ingestion_job
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- Uploaded bytes, kept apart from the document row.
--
-- Two reasons for a separate table rather than a column on document. Listings
-- and status polls read document constantly and must never drag a multi-megabyte
-- payload along with them; and the bytes are written once and read once, by the
-- ingestion worker, which is a very different access pattern from the metadata.
--
-- Object storage would be the usual home for this, and is the obvious upgrade.
-- It is deliberately out of scope for the first version: one fewer service to
-- run, and the bytes stay inside the same transaction and the same tenant
-- isolation as everything else.

CREATE TABLE document_content (
    document_id uuid    PRIMARY KEY,
    tenant_id   uuid    NOT NULL REFERENCES tenant (id) ON DELETE CASCADE,
    bytes       bytea   NOT NULL,
    CONSTRAINT document_content_document_fk FOREIGN KEY (tenant_id, document_id)
        REFERENCES document (tenant_id, id) ON DELETE CASCADE,
    CONSTRAINT document_content_not_empty CHECK (octet_length(bytes) > 0)
);

ALTER TABLE document_content ENABLE ROW LEVEL SECURITY;
ALTER TABLE document_content FORCE ROW LEVEL SECURITY;

CREATE POLICY document_content_tenant_isolation ON document_content
    FOR ALL
    USING (tenant_id = current_tenant_id())
    WITH CHECK (tenant_id = current_tenant_id());

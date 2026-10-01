-- Tenant isolation enforced by the database.
--
-- The application also filters by tenant_id in its queries, but that filter is
-- a convenience, not the guarantee. The guarantee is here: a statement that
-- forgets the filter returns nothing instead of another tenant's rows.
--
-- How the context travels: the service sets the "glosa.tenant_id" run-time
-- parameter on the connection for the duration of each unit of work, and the
-- policies below compare it against each row.
--
-- Note that this protects against a missing filter, not against a wrong tenant
-- id. Deciding which tenant the caller belongs to stays an authentication
-- concern, and is taken from the verified access token, never from input.

CREATE FUNCTION current_tenant_id() RETURNS uuid
LANGUAGE sql STABLE AS $$
    -- The second argument makes current_setting return NULL instead of raising
    -- when the parameter was never set, so an unset context denies everything
    -- rather than failing in an unhelpful place.
    SELECT nullif(current_setting('glosa.tenant_id', true), '')::uuid;
$$;

COMMENT ON FUNCTION current_tenant_id() IS
    'Tenant the current unit of work is scoped to, or NULL when no context is set. A NULL context matches no rows, which is the safe default.';

-- app_user is included: the login flow resolves the tenant from the submitted
-- slug and sets the context before it ever reads a user row.
ALTER TABLE app_user ENABLE ROW LEVEL SECURITY;
ALTER TABLE app_user FORCE ROW LEVEL SECURITY;

CREATE POLICY app_user_tenant_isolation ON app_user
    FOR ALL
    USING (tenant_id = current_tenant_id())
    WITH CHECK (tenant_id = current_tenant_id());

ALTER TABLE collection ENABLE ROW LEVEL SECURITY;
ALTER TABLE collection FORCE ROW LEVEL SECURITY;

CREATE POLICY collection_tenant_isolation ON collection
    FOR ALL
    USING (tenant_id = current_tenant_id())
    WITH CHECK (tenant_id = current_tenant_id());

ALTER TABLE document ENABLE ROW LEVEL SECURITY;
ALTER TABLE document FORCE ROW LEVEL SECURITY;

CREATE POLICY document_tenant_isolation ON document
    FOR ALL
    USING (tenant_id = current_tenant_id())
    WITH CHECK (tenant_id = current_tenant_id());

ALTER TABLE chunk ENABLE ROW LEVEL SECURITY;
ALTER TABLE chunk FORCE ROW LEVEL SECURITY;

CREATE POLICY chunk_tenant_isolation ON chunk
    FOR ALL
    USING (tenant_id = current_tenant_id())
    WITH CHECK (tenant_id = current_tenant_id());

ALTER TABLE ingestion_job ENABLE ROW LEVEL SECURITY;
ALTER TABLE ingestion_job FORCE ROW LEVEL SECURITY;

CREATE POLICY ingestion_job_tenant_isolation ON ingestion_job
    FOR ALL
    USING (tenant_id = current_tenant_id())
    WITH CHECK (tenant_id = current_tenant_id());

-- FORCE above makes the policies apply to the table owner as well, so a
-- migration that backfills tenant-scoped data has to opt out for its own
-- transaction:
--
--   ALTER TABLE document NO FORCE ROW LEVEL SECURITY;
--   -- ... backfill ...
--   ALTER TABLE document FORCE ROW LEVEL SECURITY;
--
-- Superusers bypass row security regardless, which is one more reason the
-- service connects as an unprivileged role.

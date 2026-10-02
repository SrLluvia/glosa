-- content_hash was declared char(64), which was the wrong choice.
--
-- In PostgreSQL char(n) has no storage or speed advantage over varchar; it only
-- adds blank padding semantics, so a value read back is not necessarily the
-- value written. For a hash that is compared for equality, that is a trap rather
-- than a guarantee of fixed width.
--
-- varchar(64) with a format check says more and risks less: it rejects anything
-- that is not lowercase hexadecimal of exactly the length SHA-256 produces.

ALTER TABLE document
    ALTER COLUMN content_hash TYPE varchar(64);

ALTER TABLE document
    ADD CONSTRAINT document_content_hash_is_sha256
    CHECK (content_hash ~ '^[0-9a-f]{64}$');

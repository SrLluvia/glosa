-- Make headings searchable, and worth more than body text.
--
-- The original index covered only the content column, which left the single
-- most descriptive part of a passage invisible to search. A section titled
-- "Holiday allowance" whose body talks about "paid leave" could not be found by
-- searching for "holiday", because the word appears nowhere in the indexed text.
--
-- Weights give the heading precedence: a passage whose title is about the query
-- is almost always a better answer than one that merely mentions the words in
-- passing, and ts_rank_cd takes the weights into account.

ALTER TABLE chunk DROP COLUMN content_tsv;

ALTER TABLE chunk
    ADD COLUMN content_tsv tsvector
    GENERATED ALWAYS AS (
        setweight(to_tsvector('english', coalesce(heading, '')), 'A')
        || setweight(to_tsvector('english', content), 'B')
    ) STORED;

-- Dropping the column dropped its index with it.
CREATE INDEX chunk_content_tsv_gin ON chunk USING gin (content_tsv);

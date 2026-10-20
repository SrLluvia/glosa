-- Keyword search that requires only some of the query's terms.
--
-- websearch_to_tsquery joins terms with AND. That is right for a search box and
-- wrong for a question: "how many days of paid leave does everyone get" matches
-- nothing, because no single passage contains all of those words.
--
-- Measured on the evaluation set, the keyword half found the right passage for
-- 17% of questions and for none of the paraphrased ones. Hybrid search was
-- therefore just semantic search wearing a second name.
--
-- Rewriting the operator to OR keeps quoted phrases and negations intact, since
-- those use different operators, and leaves ranking to sort the many weak
-- matches rather than a filter discarding them all.

CREATE OR REPLACE FUNCTION any_term(query text) RETURNS tsquery
LANGUAGE sql IMMUTABLE PARALLEL SAFE AS $$
    SELECT cast(
        nullif(replace(cast(websearch_to_tsquery('english', query) AS text), '&', '|'), '')
        AS tsquery);
$$;

COMMENT ON FUNCTION any_term(text) IS
    'Parses a natural-language query into a tsquery whose terms are joined with OR, so a question matches passages containing some of its words rather than all of them.';

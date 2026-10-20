package dev.glosa.core.search;

/**
 * Which halves of retrieval to use.
 *
 * <p>Exposed rather than hidden because it is how the claim "hybrid beats
 * either half" is checked instead of assumed, and because a disappointing
 * result is far easier to diagnose when each half can be run alone.
 */
public enum SearchMode {

    /** Words only. Strong on names and numbers, blind to paraphrase. */
    KEYWORD,

    /** Meaning only. Handles paraphrase, unreliable on acronyms and versions. */
    SEMANTIC,

    /** Both, merged by rank. The default, and what the product is for. */
    HYBRID;

    boolean usesKeyword() {
        return this != SEMANTIC;
    }

    boolean usesSemantic() {
        return this != KEYWORD;
    }
}

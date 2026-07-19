package com.usql.backend;

/**
 * Options controlling SQL generation output.
 */
public record GenerateOptions(
    /**
     * Identifier quoting style.
     * Currently unused — all backends use their default quoting.
     * Reserved for future use when per-statement quoting control is needed.
     */
    QuoteStyle quoteStyle,

    /** Whether to add newlines/indentation */
    boolean prettyPrint,

    /** Indent string when prettyPrint is true */
    String indent,

    /** Emit source comments for traceability */
    boolean emitComments
) {
    public enum QuoteStyle {
        /** Use the dialect's default quoting rule */
        DEFAULT,
        /** Always quote all identifiers */
        ALWAYS,
        /** Only quote reserved words */
        RESERVED_ONLY,
        /** Never quote — caller takes responsibility */
        NEVER
    }

    public static final GenerateOptions DEFAULTS = new GenerateOptions(
        QuoteStyle.DEFAULT, true, "  ", false
    );

    public static final GenerateOptions MINIMAL = new GenerateOptions(
        QuoteStyle.DEFAULT, false, "", false
    );
}

package com.usql.ir;

import java.util.Set;

/**
 * Wrapper for the fully analyzed Semantic IR.
 * Produced by the SemanticAnalyzer, consumed by Backends.
 */
public record SemanticIR(
    IRStatement rootStatement
) {
    public Set<Capability> capabilities() {
        return rootStatement.capabilities();
    }
}

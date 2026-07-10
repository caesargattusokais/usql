package com.usql.capability;

import com.usql.dialect.Dialect;
import com.usql.ir.Capability;
import com.usql.ir.IRStatement;

import java.util.*;

/**
 * Checks whether a Semantic IR statement's required capabilities
 * are supported by the target dialect.
 *
 * Output drives polyfill (if available) or error (if fatal).
 */
public class CapabilityChecker {

    private final Map<Capability, Severity> fatalCapabilities;

    public CapabilityChecker() {
        this.fatalCapabilities = new HashMap<>();

        // ── ERROR: truly untranslatable ──
        fatalCapabilities.put(Capability.RECURSIVE_CTE, Severity.ERROR);

        // ── WARNING: can work around with caveats ──
        fatalCapabilities.put(Capability.WINDOW_FUNCTION, Severity.WARNING);
        fatalCapabilities.put(Capability.ARRAY_TYPE, Severity.WARNING);
        fatalCapabilities.put(Capability.DEFERRABLE_FK, Severity.WARNING);
        fatalCapabilities.put(Capability.GENERATED_COLUMN, Severity.WARNING);
        fatalCapabilities.put(Capability.FULL_OUTER_JOIN, Severity.WARNING);

        // ── INFO: clean polyfill available ──
        fatalCapabilities.put(Capability.LIMIT_OFFSET, Severity.INFO);
        fatalCapabilities.put(Capability.BOOLEAN_TYPE, Severity.INFO);
        fatalCapabilities.put(Capability.AUTO_INCREMENT, Severity.INFO);
        fatalCapabilities.put(Capability.CONCAT_WITH_NULL, Severity.INFO);
        fatalCapabilities.put(Capability.PARTIAL_INDEX, Severity.INFO);
        fatalCapabilities.put(Capability.ENUM_TYPE, Severity.INFO);
        fatalCapabilities.put(Capability.RETURNING_CLAUSE, Severity.INFO);
        fatalCapabilities.put(Capability.SELECT_WITHOUT_FROM, Severity.INFO);
        fatalCapabilities.put(Capability.HAVING, Severity.INFO);
        fatalCapabilities.put(Capability.TRUNCATE_TABLE, Severity.INFO);
        fatalCapabilities.put(Capability.REPLACE_INTO, Severity.INFO);
        fatalCapabilities.put(Capability.ON_DUPLICATE_KEY_UPDATE, Severity.INFO);
        fatalCapabilities.put(Capability.INTERVAL_ARITHMETIC, Severity.INFO);
        fatalCapabilities.put(Capability.MERGE_INTO, Severity.INFO);
    }

    /**
     * Check a statement against a target dialect.
     */
    public CapabilityReport check(IRStatement statement, Dialect dialect) {
        Set<Capability> required = statement.capabilities();
        Set<Capability> missing = EnumSet.noneOf(Capability.class);
        Set<Capability> polyfillable = EnumSet.noneOf(Capability.class);
        List<CapabilityReport.Finding> findings = new ArrayList<>();

        for (Capability cap : required) {
            if (!dialect.supports(cap)) {
                missing.add(cap);

                Severity severity = fatalCapabilities.getOrDefault(cap, Severity.WARNING);
                if (PolyfillEngine.canPolyfill(cap)) {
                    polyfillable.add(cap);
                    findings.add(new CapabilityReport.Finding(
                        cap, Severity.WARNING,
                        "Feature '" + cap.name() + "' is not natively supported by "
                            + dialect.displayName() + " — applying polyfill"
                    ));
                } else {
                    findings.add(new CapabilityReport.Finding(
                        cap, severity,
                        "Feature '" + cap.name() + "' is required but "
                            + dialect.displayName() + " does not support it"
                    ));
                }
            }
        }

        return new CapabilityReport(missing, polyfillable, findings, missing.isEmpty());
    }

    public enum Severity { ERROR, WARNING, INFO }

    /**
     * Report from a capability check.
     */
    public record CapabilityReport(
        Set<Capability> missingCapabilities,
        Set<Capability> polyfillableCapabilities,
        List<Finding> findings,
        boolean allSupported
    ) {
        public boolean hasMissing() { return !missingCapabilities.isEmpty(); }
        public boolean hasFatal() {
            return findings.stream().anyMatch(f -> f.severity() == Severity.ERROR);
        }

        public record Finding(Capability capability, Severity severity, String message) {}
    }
}

package org.fslab.journal.ring;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * A domain quantity together with its raw representation, unit/coordinate basis
 * and optional conversion diagnostics.
 *
 * <p>Import always preserves {@code raw}; normalization to {@code value} happens
 * only in the derivation layer. Values that cannot be converted stay in place
 * with a diagnostic rather than being dropped to make a picture render.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record DomainValue(
        String raw,
        Object value,
        String unit,
        String basis,
        List<String> diagnostics) {

    public static DomainValue raw(String raw) {
        return new DomainValue(raw, null, null, null, null);
    }

    public static DomainValue of(String raw, Object value, String unit, String basis) {
        return new DomainValue(raw, value, unit, basis, null);
    }

    public static DomainValue invalid(String raw, String unit, String diagnostic) {
        return new DomainValue(raw, null, unit, null, List.of(diagnostic));
    }

    public boolean isValid() {
        return value != null && (diagnostics == null || diagnostics.isEmpty());
    }
}

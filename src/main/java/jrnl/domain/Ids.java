package jrnl.domain;

/**
 * Stable identity scheme. Every displayed value is addressable.
 *
 *  - source nodes:  src:{kind}:{coordinate}
 *  - derived nodes: der:{kind}:{coord}@{derivationEventId}
 *
 * The derivation-event suffix pins a value to the exact rule version + source
 * version + decisions that produced it, so historical views are never silently
 * overwritten by the newest computation.
 */
public final class Ids {

    private Ids() {
    }

    public static String src(String kind, String coordinate) {
        return "src:" + kind + ":" + coordinate;
    }

    public static String der(String kind, String coordinate, String derivationEventId) {
        return "der:" + kind + ":" + coordinate + "@" + derivationEventId;
    }

    public static String event(String caseId, long n) {
        return "evt:" + caseId + ":" + Long.toUnsignedString(n);
    }
}

package jbd2lab.core;

import java.util.ArrayList;
import java.util.List;

/** Output of scanning a physical journal ring: candidates, diagnostics and provenance. */
public final class ScanResult {
    public final List<Txn> txns = new ArrayList<>();
    /** slot -> short role label ("descriptor", "data:fs=...", "commit", "revoke", "gap", ...) */
    public final List<String> slotRoles = new ArrayList<>();
    public final List<Diagnostic> diagnostics = new ArrayList<>();
    /** ring slot where resync after the first checksum failure would continue, or -1 */
    public int resyncSlot = -1;
    /** first transaction whose boundary is uncertain after a checksum break, or -1 */
    public long uncertainAfterSeq = -1L;
    public boolean wrapped;          // scan traversed the physical end of the ring
    public boolean blockSizeInvalid;
    public int ringStart;
    public int ringLength;
    public int blockSize;
    public int checksumType;
    public long headSequence;

    public void diag(String severity, String code, String message, int slot) {
        diagnostics.add(new Diagnostic(severity, code, message, slot));
    }

    public static final class Diagnostic {
        public final String severity, code, message;
        public final int slot;
        public Diagnostic(String severity, String code, String message, int slot) {
            this.severity = severity;
            this.code = code;
            this.message = message;
            this.slot = slot;
        }
    }
}

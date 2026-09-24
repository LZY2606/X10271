package org.jbd2lab.scan;

import java.util.List;

/**
 * One legal interpretation of the scanned ring.
 *
 * <p>The prefix candidate is certain. After a checksum break the boundary retains uncertainty:
 * each independently re-synchronised suffix becomes its own candidate; when none can be proven,
 * the suffix is reported as uncertainty rather than silently merged into the prefix.
 */
public record ScanCandidate(String candidateId, boolean prefix, boolean chosen,
                            List<TransactionGroup> transactions, List<ScanBlock> walk,
                            List<LabEvent> events) {
}

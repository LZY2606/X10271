package fsreplay.store;

/** Two users edited from the same version; the second save is rejected but kept as a candidate. */
public class ConflictException extends Exception {
    public final String pointerName;
    public final String currentVersionId;
    public final String attemptedBaseVersionId;
    public final String keptCandidateVersionId;

    public ConflictException(String pointerName, String currentVersionId, String attemptedBaseVersionId,
                             String keptCandidateVersionId) {
        super("conflict on '" + pointerName + "': current=" + currentVersionId
                + " attempted-base=" + attemptedBaseVersionId
                + " — candidate kept as " + keptCandidateVersionId);
        this.pointerName = pointerName;
        this.currentVersionId = currentVersionId;
        this.attemptedBaseVersionId = attemptedBaseVersionId;
        this.keptCandidateVersionId = keptCandidateVersionId;
    }
}

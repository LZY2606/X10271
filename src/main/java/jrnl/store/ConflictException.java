package jrnl.store;

/** Two editors started from the same version: the second commit must conflict. */
public class ConflictException extends RuntimeException {

    private final String caseId;
    private final String expectedBaseEventId;
    private final String actualHeadEventId;

    public ConflictException(String caseId, String expectedBaseEventId, String actualHeadEventId) {
        super("conflict on case " + caseId + ": base " + expectedBaseEventId
                + " is no longer head (head=" + actualHeadEventId + "); merge required");
        this.caseId = caseId;
        this.expectedBaseEventId = expectedBaseEventId;
        this.actualHeadEventId = actualHeadEventId;
    }

    public String caseId() {
        return caseId;
    }

    public String expectedBaseEventId() {
        return expectedBaseEventId;
    }

    public String actualHeadEventId() {
        return actualHeadEventId;
    }
}

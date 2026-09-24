package lab.jbd2.model;
import java.util.List;

/** A legal but not-yet-chosen branch. Unselected candidates are retained forever. */
public record Candidate(
        String candidateId,
        String branch,
        String status,
        String rationale,
        List<String> eventIds,
        String parentVersionId) {
}

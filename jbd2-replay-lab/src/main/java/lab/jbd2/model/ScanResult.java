package lab.jbd2.model;

import java.util.List;

/** Output of the journal scanner over the durable ring prefix. */
public record ScanResult(
        long cursorStart,
        long durableBlocks,
        boolean ringWrapped,
        List<JournalTxn> transactions,
        List<RuleEvent> events,
        List<String> fatalDiagnostics) {

    public boolean fatal() {
        return !fatalDiagnostics.isEmpty();
    }
}

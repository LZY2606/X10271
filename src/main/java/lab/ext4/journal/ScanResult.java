package lab.ext4.journal;

import java.util.ArrayList;
import java.util.List;

public class ScanResult {
    public final List<TxGroup> groups = new ArrayList<>();
    public final List<RevokeRecord> revokes = new ArrayList<>();
    public final List<Anomaly> anomalies = new ArrayList<>();
    public final List<String> notes = new ArrayList<>();
    public final List<Diagnostic> diagnostics = new ArrayList<>();
    /** First physical ring position whose content is untrusted; -1 if none. */
    public int checksumBreakPos = -1;
    public boolean blockSizeValid = true;
    public int ringLength;
}

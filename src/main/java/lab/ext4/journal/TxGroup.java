package lab.ext4.journal;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A transaction assembled in physical ring order (not integer-sorted sequence).
 */
public class TxGroup {
    public final long seq;
    public final int descriptorRingPos;
    public int commitRingPos = -1;
    public boolean committed;
    public boolean commitChecksumOk = true;
    public boolean behindChecksumBreak;
    public final LinkedHashMap<Long, byte[]> data = new LinkedHashMap<>();
    public final LinkedHashMap<Long, DescriptorTag> tags = new LinkedHashMap<>();

    public TxGroup(long seq, int descriptorRingPos) {
        this.seq = seq;
        this.descriptorRingPos = descriptorRingPos;
    }

    public Map<Long, byte[]> liveWrites() {
        LinkedHashMap<Long, byte[]> writes = new LinkedHashMap<>();
        for (Map.Entry<Long, DescriptorTag> e : tags.entrySet()) {
            if (!e.getValue().isDeleted() && data.containsKey(e.getKey())) {
                writes.put(e.getKey(), data.get(e.getKey()));
            }
        }
        return writes;
    }
}

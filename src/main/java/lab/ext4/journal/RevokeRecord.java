package lab.ext4.journal;

import java.util.List;

public record RevokeRecord(long seq, int ringPos, List<Long> revokedBlocks) {
}

package lab.ext4.journal;

import java.util.List;
import java.util.Map;

/**
 * Imported raw material. Nothing here is normalized: blockSize and ringStart keep
 * their imported representation; bytes are stored verbatim; crashPoint is measured
 * in journal blocks from the ring start along the physical ring.
 */
public record JournalImage(
        String sourceId,
        String rawBlockSizeRepr,
        int blockSize,
        String rawRingStartRepr,
        int ringStart,
        List<byte[]> journalBlocks,
        Map<Long, byte[]> fsSnapshot,
        int checksumVersion,
        int crashPoint) {
}

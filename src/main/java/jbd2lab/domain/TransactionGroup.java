package jbd2lab.domain;

import java.util.List;

/**
 * One transaction as assembled by the scanner from a descriptor through its
 * revoke/data blocks to a commit. {@code committed} means a commit block carrying
 * the same sequence was found; {@code checksumOk} validates the CRC32C family
 * against the stored commit checksum. Only fully committed, checksum-valid groups
 * up to the first uncertainty boundary are replayed (engine policy).
 *
 * @param members      every block belonging to the group in ring order
 * @param covers       fs-block -> ordered journal coordinates that write it (repeat tracking)
 * @param revoked      fs blocks named by revoke blocks of this sequence
 * @param checksumOk   null when no stored checksum; true/false when present
 */
public record TransactionGroup(
        long sequence,
        List<BlockRef> members,
        BlockRef descriptor,
        List<BlockRef> revokes,
        List<BlockRef> dataBlocks,
        BlockRef commit,
        boolean committed,
        Boolean checksumOk,
        java.util.Map<Long, List<BlockRef>> covers,
        List<Long> revoked,
        String note) {

    public boolean replayable() {
        return committed && (checksumOk == null || checksumOk);
    }
}

package jbd2lab.domain;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds valid (and deliberately corruptible) JBD2-style journal images for fixtures.
 * Produces a physical ring: blocks are laid out in physical order starting at the
 * given ring-start slot and wrap around the capacity exactly like a real journal.
 */
public final class JournalBuilder {
    private final int blockSize;
    private final int capacity;
    private final int ringStartAbs;
    private final byte[] image;
    private int cursor; // ring slot to write next
    private final Map<Integer, String> slotLabels = new LinkedHashMap<>();

    public JournalBuilder(int blockSize, int capacity, int ringStartAbs, int firstRingSlot) {
        this.blockSize = blockSize;
        this.capacity = capacity;
        this.ringStartAbs = ringStartAbs;
        this.image = new byte[(ringStartAbs + capacity) * blockSize];
        this.cursor = Math.floorMod(firstRingSlot, capacity);
    }

    public int blockSize() {
        return blockSize;
    }

    public int capacity() {
        return capacity;
    }

    public int ringStartAbs() {
        return ringStartAbs;
    }

    public int cursor() {
        return cursor;
    }

    public Map<Integer, String> slotLabels() {
        return slotLabels;
    }

    public byte[] image() {
        return image;
    }

    public int absOf(int ringSlot) {
        return ringStartAbs + ringSlot;
    }

    /** One mapped (fsBlock -> payload) entry inside a descriptor. */
    public record Entry(long fsBlock, byte[] payload) {}

    /**
     * Appends a full transaction: optional revoke block, descriptor + data blocks, commit.
     * Returns the physical ring slots used, in order (may cross the ring wrap point).
     */
    public int[] appendTransaction(long sequence, List<Long> revoke, List<Entry> entries) {
        List<Integer> slots = new ArrayList<>();
        if (revoke != null && !revoke.isEmpty()) {
            byte[] rb = revokeBlock(sequence, revoke);
            slots.add(writeSlot(rb, "revoke seq=" + CyclicSeq.hex(sequence)));
        }
        byte[] desc = descriptorBlock(sequence, entries);
        slots.add(writeSlot(desc, "descriptor seq=" + CyclicSeq.hex(sequence)));
        for (Entry e : entries) {
            byte[] data = dataBlock(sequence, e.payload());
            slots.add(writeSlot(data, "data fsblk=" + e.fsBlock() + " seq=" + CyclicSeq.hex(sequence)));
        }
        byte[] commit = commitBlock(sequence);
        slots.add(writeSlot(commit, "commit seq=" + CyclicSeq.hex(sequence)));
        return slots.stream().mapToInt(Integer::intValue).toArray();
    }

    /** Writes a partial (torn) transaction: head + some data blocks, deliberately no commit. */
    public int[] appendHalfTransaction(long sequence, List<Entry> entries, int dataBlocksWritten) {
        List<Integer> slots = new ArrayList<>();
        byte[] desc = descriptorBlock(sequence, entries);
        slots.add(writeSlot(desc, "descriptor(torn) seq=" + CyclicSeq.hex(sequence)));
        for (int i = 0; i < Math.min(dataBlocksWritten, entries.size()); i++) {
            byte[] data = dataBlock(sequence, entries.get(i).payload());
            slots.add(writeSlot(data, "data(torn) fsblk=" + entries.get(i).fsBlock()));
        }
        return slots.stream().mapToInt(Integer::intValue).toArray();
    }

    private int writeSlot(byte[] block, String label) {
        int slot = cursor;
        System.arraycopy(block, 0, image, (ringStartAbs + slot) * blockSize, blockSize);
        slotLabels.put(slot, label);
        cursor = (cursor + 1) % capacity;
        return slot;
    }

    public byte[] header(int type, long sequence) {
        byte[] b = new byte[blockSize];
        Bytes.putBE32(b, 0, Jbd2Format.JBD2_MAGIC);
        Bytes.putBE32(b, 4, type);
        Bytes.putBE32(b, 8, sequence & 0xffffffffL);
        return b;
    }

    public byte[] descriptorBlock(long sequence, List<Entry> entries) {
        byte[] b = header(Jbd2Format.DESC_BLOCK, sequence);
        int off = Jbd2Format.BLOCK_HEADER_SIZE;
        for (int i = 0; i < entries.size(); i++) {
            Entry e = entries.get(i);
            int flags = i == entries.size() - 1 ? Jbd2Format.TAG_FLAG_LAST_TAG : 0;
            Bytes.putBE64(b, off, e.fsBlock());
            Bytes.putBE32(b, off + 8, flags);
            Bytes.putBE32(b, off + 12, Crc32c.of(dataBlock(sequence, e.payload())));
            off += Jbd2Format.TAG_SIZE;
        }
        long crc = Crc32c.of(b, 0, blockSize - Jbd2Format.CHECKSUM_SIZE);
        Bytes.putBE32(b, blockSize - Jbd2Format.CHECKSUM_SIZE, crc);
        return b;
    }

    public byte[] revokeBlock(long sequence, List<Long> revoked) {
        byte[] b = header(Jbd2Format.REVOKE_BLOCK, sequence);
        Bytes.putBE32(b, Jbd2Format.REVOKE_COUNT_OFF, revoked.size());
        int off = Jbd2Format.REVOKE_ENTRIES_OFF;
        for (long blk : revoked) {
            Bytes.putBE64(b, off, blk);
            off += 8;
        }
        long crc = Crc32c.of(b, 0, blockSize - Jbd2Format.CHECKSUM_SIZE);
        Bytes.putBE32(b, blockSize - Jbd2Format.CHECKSUM_SIZE, crc);
        return b;
    }

    public byte[] dataBlock(long sequence, byte[] payload) {
        byte[] b = header(0, sequence); // type 0 = plain data block carrying header sequence
        int n = Math.min(payload.length, blockSize - Jbd2Format.BLOCK_HEADER_SIZE);
        System.arraycopy(payload, 0, b, Jbd2Format.BLOCK_HEADER_SIZE, n);
        return b;
    }

    public byte[] commitBlock(long sequence) {
        byte[] b = header(Jbd2Format.COMMIT_BLOCK, sequence);
        Bytes.putBE32(b, 12, Jbd2Format.CKSUM_V3_CRC32C);
        Bytes.putBE32(b, 16, 0); // seconds
        Bytes.putBE32(b, 24, 0); // nanoseconds
        byte[] copy = b.clone();
        Bytes.putBE32(copy, Jbd2Format.COMMIT_CHECKSUM_OFF, 0);
        long crc = Crc32c.of(copy, 0, blockSize);
        Bytes.putBE32(b, Jbd2Format.COMMIT_CHECKSUM_OFF, crc);
        return b;
    }

    /** Flips one byte of a stored checksum at an absolute journal block. */
    public void corruptChecksumAtAbs(int absBlock) {
        int off = absBlock * blockSize + Jbd2Format.COMMIT_CHECKSUM_OFF;
        image[off] ^= (byte) 0xff;
    }

    /** Corrupts the tail (descriptor/revoke body) checksum at an absolute block. */
    public void corruptTailChecksumAtAbs(int absBlock) {
        int off = (absBlock + 1) * blockSize - 1;
        image[off] ^= (byte) 0xff;
    }

    /** Zeroes one byte inside a data payload at an absolute block (data CRC then fails). */
    public void corruptDataAtAbs(int absBlock, int payloadOffset) {
        int off = absBlock * blockSize + Jbd2Format.BLOCK_HEADER_SIZE + payloadOffset;
        image[off] ^= (byte) 0xff;
    }

    /** Puts a zeroed (unused) slot back. */
    public void zeroSlot(int ringSlot) {
        int off = (ringStartAbs + ringSlot) * blockSize;
        for (int i = 0; i < blockSize; i++) {
            image[off + i] = 0;
        }
        slotLabels.remove(ringSlot);
    }
}

package org.journalreplay.casepack;

import java.nio.file.Files;
import java.nio.file.Path;
import org.journalreplay.jbd2.ChecksumVersion;
import org.journalreplay.jbd2.JournalImageBuilder;

/**
 * Generates the offline case package "ext4 日志演算室" binary fixtures.
 * Layout (blockSize=1024 bytes, log start at fs block 64, 20 journal blocks):
 *
 * <pre>
 *  0  SB_V2  firstSeq=0xFFFFFFFE, checksum v3
 *  1  DESC  seq=FE  -> fs 10, 11        2 DATA "A1"  3 DATA "B1"   4 COMMIT ok
 *  5  DESC  seq=FF  -> fs 12            6 DATA "C1"                7 COMMIT ok
 *  8  DESC  seq=0   -> fs 10, 13        9 DATA "A2" 10 DATA "D1"
 * 11  REVOKE seq=0  cancels fs 11      12 COMMIT ok
 * 13  DESC  seq=1   -> fs 11, 14       14 DATA "B2" 15 DATA "E1"  16 COMMIT corrupted
 * 17  DESC  seq=2   -> fs 15           18 DATA "F1"                (no commit)
 * crash point = ring index 19
 * </pre>
 *
 * Exercises: ring wraparound (FE,FF,0,1,2), half transaction (seq 2),
 * duplicate block (fs 10 in seq FE and seq 0), rewrite after revoke
 * (fs 11 revoked in seq 0, rewritten in seq 1), checksum break (seq 1 commit),
 * and — via a separate source record — an invalid block size of 1000 bytes.
 */
public final class FixtureGenerator {
    public static final int BLOCK_SIZE = 1024;
    public static final long LOG_START_BLOCK = 64;
    public static final int JOURNAL_BLOCKS = 20;
    public static final int CRASH_POINT = 19;
    public static final byte[] UUID = new byte[]{
            0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77, (byte) 0x88,
            (byte) 0x99, (byte) 0xaa, (byte) 0xbb, (byte) 0xcc,
            (byte) 0xdd, (byte) 0xee, (byte) 0x0f, 0x10};

    public static byte[] journalImage() {
        return new JournalImageBuilder(BLOCK_SIZE, ChecksumVersion.V3_CRC32C_PER_TAG, UUID)
                .superblockV2(0xFFFFFFFEL, JOURNAL_BLOCKS, 1)
                .descriptor(0xFFFFFFFEL, 10, 11).data("A1").data("B1").commit(0xFFFFFFFEL)
                .descriptor(0xFFFFFFFFL, 12).data("C1").commit(0xFFFFFFFFL)
                .descriptor(0, 10, 13).data("A2").data("D1").revoke(0, 11).commit(0)
                .descriptor(1, 11, 14).data("B2").data("E1").commitCorrupted(1)
                .descriptor(2, 15).data("F1")
                .build();
    }

    public static byte[] baseBlocksImage() {
        int blocks = 32;
        byte[] image = new byte[blocks * BLOCK_SIZE];
        for (int i = 0; i < blocks; i++) {
            byte[] tag = ("BASE" + i).getBytes(java.nio.charset.StandardCharsets.US_ASCII);
            for (int j = 0; j < BLOCK_SIZE; j++) image[i * BLOCK_SIZE + j] = tag[j % tag.length];
        }
        return image;
    }

    public static void main(String[] args) throws Exception {
        Path dir = Path.of(args[0]);
        Files.createDirectories(dir);
        Files.write(dir.resolve("journal-v3.bin"), journalImage());
        Files.write(dir.resolve("blocks-base.bin"), baseBlocksImage());
        System.out.println("fixtures written to " + dir);
    }
}

package jrnl.fixture;

import jrnl.domain.ChecksumVersion;
import jrnl.fixture.BlockImage.TagSpec;

import java.util.ArrayList;
import java.util.List;

/**
 * "ext4 日志演算室：事务描述块、revoke 与崩溃点回放" — the offline domain case
 * pack. One continuous narrative (not a generic spreadsheet): a 31-image-block
 * journal where a 16-slot ring wraps into its second lap, sequence numbers wrap
 * at 32 bits, and every required boundary condition occurs on the ring.
 *
 * Image layout (block size 4096 B; ring start image#1; 16 ring slots):
 *  #0  journal superblock v2
 *  #1-#2   T1 seq=100 complete: d(fs100 "A1"), commit
 *  #3-#4   T2 seq=101 complete: d(fs100 "A2", fs101 "B"), commit   [duplicate block]
 *  #5      T3 seq=102 revoke(fs200)
 *  #6-#8   T4 seq=103 complete: d(fs200 "C", fs201 "D"), commit    [fs200 revoked]
 *  #9-#11  T5 seq=104 complete: d(fs300 "E1", fs200 "C2"), commit  [revoke-then-rewrite fs200]
 *  #12-#13 T6 seq=105 complete: d(fs400 "F"), commit
 *  #14-#16 T7 seq=106: d(fs500 "G"), COMMIT CHECKSUM CORRUPT        [checksum boundary]
 *  #17     empty (ring slot 16 = start of second lap)
 *  #18-#20 T8 seq=0xFFFFFFFE complete: d(fs300 "E2"), commit       [sequence wrap]
 *  #21     T9 seq=0x00000002 revoke(fs300)  (newer than T8 on ring)
 *  #22-#27 empty
 *  #28-#29 T10 seq=3 d(fs300 "E3") with NO commit, crash at image#29 [half transaction; rewrite]
 */
public final class LabCase {

    public static final String CASE_ID = "lab-jbd2-crashroom";
    public static final int BLOCK_SIZE = 4096;
    public static final int RING_START_IMAGE = 1;
    public static final int RING_SLOTS = 16;
    public static final int CRASH_IMAGE = 29;
    public static final ChecksumVersion CV = ChecksumVersion.V3_CRC32C;

    private LabCase() {
    }

    public record ImageBlock(int imageIndex, String kind, long sequence, byte[] bytes, String note) {
    }

    public static List<ImageBlock> buildImage() {
        List<ImageBlock> img = new ArrayList<>();
        int i = 0;
        img.add(new ImageBlock(i++, "superblock", 0,
                BlockImage.superblockV2(BLOCK_SIZE, RING_SLOTS + 1, RING_START_IMAGE, 3),
                "journal superblock v2: s_blocksize=4096, s_first=1, s_maxlen=17, s_sequence=3"));

        // T1 seq 100
        img.add(new ImageBlock(i++, "descriptor", 100,
                BlockImage.descriptor(BLOCK_SIZE, 100, new TagSpec[]{TagSpec.of(100)}, CV),
                "T1 descriptor tag -> fs block 100"));
        img.add(new ImageBlock(i++, "data", 100, BlockImage.data(BLOCK_SIZE, "T1-seq100-fs100=A1"), "T1 data A1"));
        img.add(new ImageBlock(i++, "commit", 100, BlockImage.commit(BLOCK_SIZE, 100, CV, false), "T1 complete commit"));

        // T2 seq 101 — duplicate fs100
        img.add(new ImageBlock(i++, "descriptor", 101,
                BlockImage.descriptor(BLOCK_SIZE, 101,
                        new TagSpec[]{TagSpec.of(100), TagSpec.of(101)}, CV),
                "T2 descriptor tags -> fs 100,101 (fs100 repeated)"));
        img.add(new ImageBlock(i++, "data", 101, BlockImage.data(BLOCK_SIZE, "T2-seq101-fs100=A2"), "T101 data A2 supersedes A1"));
        img.add(new ImageBlock(i++, "data", 101, BlockImage.data(BLOCK_SIZE, "T2-seq101-fs101=B"), "fs101 B"));
        img.add(new ImageBlock(i++, "commit", 101, BlockImage.commit(BLOCK_SIZE, 101, CV, false), "T2 complete commit"));

        // T3 seq 102 revoke fs200
        img.add(new ImageBlock(i++, "revoke", 102, BlockImage.revoke(BLOCK_SIZE, 102, new long[]{200}),
                "T3 revoke record for fs block 200"));

        // T4 seq 103 — fs200 write is older than the revoke
        img.add(new ImageBlock(i++, "descriptor", 103,
                BlockImage.descriptor(BLOCK_SIZE, 103,
                        new TagSpec[]{TagSpec.of(200), TagSpec.of(201)}, CV),
                "T4 descriptor -> fs 200,201"));
        img.add(new ImageBlock(i++, "data", 103, BlockImage.data(BLOCK_SIZE, "T4-seq103-fs200=C"), "fs200 C (revoked)"));
        img.add(new ImageBlock(i++, "data", 103, BlockImage.data(BLOCK_SIZE, "T4-seq103-fs201=D"), "fs201 D"));
        img.add(new ImageBlock(i++, "commit", 103, BlockImage.commit(BLOCK_SIZE, 103, CV, false), "T4 complete commit"));

        // T5 seq 104 — rewrite fs200 AFTER the revoke survives; fs300 first value
        img.add(new ImageBlock(i++, "descriptor", 104,
                BlockImage.descriptor(BLOCK_SIZE, 104,
                        new TagSpec[]{TagSpec.of(300), TagSpec.of(200)}, CV),
                "T5 descriptor -> fs 300,200 (fs200 rewritten after revoke)"));
        img.add(new ImageBlock(i++, "data", 104, BlockImage.data(BLOCK_SIZE, "T5-seq104-fs300=E1"), "fs300 E1"));
        img.add(new ImageBlock(i++, "data", 104, BlockImage.data(BLOCK_SIZE, "T5-seq104-fs200=C2"), "fs200 C2 survives revoke"));
        img.add(new ImageBlock(i++, "commit", 104, BlockImage.commit(BLOCK_SIZE, 104, CV, false), "T5 complete commit"));

        // T6 seq 105
        img.add(new ImageBlock(i++, "descriptor", 105,
                BlockImage.descriptor(BLOCK_SIZE, 105, new TagSpec[]{TagSpec.of(400)}, CV),
                "T6 descriptor -> fs 400"));
        img.add(new ImageBlock(i++, "data", 105, BlockImage.data(BLOCK_SIZE, "T6-seq105-fs400=F"), "fs400 F"));
        img.add(new ImageBlock(i++, "commit", 105, BlockImage.commit(BLOCK_SIZE, 105, CV, false), "T6 complete commit"));

        // T7 seq 106 — corrupt commit checksum
        img.add(new ImageBlock(i++, "descriptor", 106,
                BlockImage.descriptor(BLOCK_SIZE, 106, new TagSpec[]{TagSpec.of(500)}, CV),
                "T7 descriptor -> fs 500"));
        img.add(new ImageBlock(i++, "data", 106, BlockImage.data(BLOCK_SIZE, "T7-seq106-fs500=G"), "fs500 G"));
        img.add(new ImageBlock(i++, "commit", 106, BlockImage.commit(BLOCK_SIZE, 106, CV, true),
                "T7 commit with checksum flipped -> boundary uncertainty"));

        // second lap, slot 16 empty
        img.add(new ImageBlock(i++, "empty", 0, BlockImage.zero(BLOCK_SIZE), "ring wraps: second lap slot 0, empty"));

        // T8 seq 0xFFFFFFFE — wraps across uint32 top
        img.add(new ImageBlock(i++, "descriptor", 0xFFFFFFFEL,
                BlockImage.descriptor(BLOCK_SIZE, 0xFFFFFFFEL,
                        new TagSpec[]{TagSpec.of(300)}, CV),
                "T8 descriptor seq wraps near 2^32, fs300 E2"));
        img.add(new ImageBlock(i++, "data", 0xFFFFFFFEL,
                BlockImage.data(BLOCK_SIZE, "T8-seqFFFFFFFE-fs300=E2"), "fs300 E2 after ring wrap"));
        img.add(new ImageBlock(i++, "commit", 0xFFFFFFFEL,
                BlockImage.commit(BLOCK_SIZE, 0xFFFFFFFEL, CV, false), "T8 complete commit across wrap"));

        // T9 seq 2 — revoke fs300; newer on ring than 0xFFFFFFFE, masks E2
        img.add(new ImageBlock(i++, "revoke", 2, BlockImage.revoke(BLOCK_SIZE, 2, new long[]{300}),
                "T9 revoke fs300 at seq 2: strictly newer than FFFFFFFE, masks E2"));

        // empty slots 22..27
        for (int k = 0; k < 6; k++) {
            img.add(new ImageBlock(i++, "empty", 0, BlockImage.zero(BLOCK_SIZE), "empty ring slot"));
        }

        // T10 seq 3 — half transaction at the crash point; rewrite fs300 survives revoke
        img.add(new ImageBlock(i++, "descriptor", 3,
                BlockImage.descriptor(BLOCK_SIZE, 3, new TagSpec[]{TagSpec.of(300)}, CV),
                "T10 descriptor fs300 E3; commit never lands before crash"));
        img.add(new ImageBlock(i++, "data", 3,
                BlockImage.data(BLOCK_SIZE, "T10-seq3-fs300=E3"), "T10 torn write — half transaction, never replayed"));

        return img;
    }
}

package jbd2lab.engine;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import jbd2lab.domain.BlockRef;
import jbd2lab.domain.BlockStatus;
import jbd2lab.domain.JournalFormat;
import jbd2lab.domain.JournalImage;
import jbd2lab.domain.ParsedBlock;
import jbd2lab.domain.RawBlock;
import jbd2lab.domain.RingGeom;
import jbd2lab.domain.TransactionGroup;
import jbd2lab.domain.ScanResult;

/**
 * Scans the journal ring from its start and reconstructs transaction groups.
 *
 * Ring walking is offset-based (wraps via {@link RingGeom#physicalFor(long)}),
 * never a plain sort of physical indices. Sequence membership is recorded but
 * ordering decisions about replay use the ring-ordered group list.
 */
public final class Scanner {

    public ScanResult scan(JournalImage image, int checksumVersion) {
        RingGeom geom = image.geom();
        List<String> diagnostics = new ArrayList<>();
        if (!geom.blockSizeValid()) {
            diagnostics.add("INVALID_BLOCK_SIZE: " + geom.blockSize());
        }

        List<ParsedBlock> blocks = new ArrayList<>();
        for (long off = 0; off < geom.total(); off++) {
            int physical = geom.physicalFor(off);
            RawBlock raw = image.at(physical);
            blocks.add(parse(raw, physical, off));
        }

        List<MutableGroup> groups = new ArrayList<>();
        MutableGroup current = null;
        for (ParsedBlock pb : blocks) {
            switch (pb.status()) {
                case DESCRIPTOR -> {
                    if (current != null) {
                        current.note = append(current.note, "NO_COMMIT_BEFORE_NEXT_DESCRIPTOR");
                        groups.add(current.close(checksumVersion, image));
                    }
                    current = new MutableGroup(pb.sequence());
                    current.members.add(pb.ref());
                    current.descriptor = pb.ref();
                    current.expectedData.addAll(tagsWithoutDeleted(pb));
                    current.tags.addAll(pb.tags());
                }
                case REVOKE -> {
                    if (current == null || pb.sequence() != current.sequence) {
                        current = startOrphan(current, groups, pb, checksumVersion, image);
                    }
                    current.members.add(pb.ref());
                    current.revokes.add(pb.ref());
                    current.revokedBlocks.addAll(pb.revoked());
                }
                case DATA -> {
                    if (current == null || pb.sequence() != current.sequence) {
                        current = startOrphan(current, groups, pb, checksumVersion, image);
                    }
                    current.members.add(pb.ref());
                    current.data.add(pb.ref());
                }
                case COMMIT -> {
                    if (current == null || pb.sequence() != current.sequence) {
                        diagnostics.add("ORPHAN_COMMIT: seq=" + pb.sequence()
                                + " at physical=" + pb.ref().physical());
                        break;
                    }
                    current.members.add(pb.ref());
                    current.commit = pb.ref();
                    current.committed = true;
                    groups.add(current.close(checksumVersion, image, pb.storedChecksum()));
                    current = null;
                }
                default -> {
                    if (current != null && pb.status() == BlockStatus.BAD_MAGIC) {
                        current.note = append(current.note,
                                "BAD_MAGIC_INSIDE_TRANSACTION@" + pb.ref().physical());
                    }
                }
            }
        }
        if (current != null) {
            current.note = append(current.note, "TRUNCATED_AT_RING_END");
            groups.add(current.close(checksumVersion, image));
        }

        List<TransactionGroup> closed = new ArrayList<>();
        long boundary = -1;
        boolean half = false;
        for (MutableGroup mg : groups) {
            TransactionGroup g = mg.build();
            closed.add(g);
            if (g.checksumOk() != null && !g.checksumOk() && boundary < 0) {
                boundary = g.descriptor().ringOffset();
            }
            if (!g.committed()) {
                half = true;
            }
        }
        return new ScanResult(geom, checksumVersion, blocks, closed, boundary, half, diagnostics);
    }

    private MutableGroup startOrphan(MutableGroup current, List<MutableGroup> groups,
                                     ParsedBlock pb, int version, JournalImage image) {
        if (current != null) {
            current.note = append(current.note, "NO_COMMIT_BEFORE_seq=" + pb.sequence());
            groups.add(current.close(version, image));
        }
        MutableGroup ng = new MutableGroup(pb.sequence());
        ng.note = append(ng.note, "GROUP_WITHOUT_DESCRIPTOR@" + pb.ref().physical());
        return ng;
    }

    private static List<Long> tagsWithoutDeleted(ParsedBlock pb) {
        List<Long> out = new ArrayList<>();
        pb.tags().forEach(t -> {
            if (!t.deleted()) {
                out.add(t.fsBlock());
            }
        });
        return out;
    }

    private static String append(String a, String b) {
        return a == null || a.isBlank() ? b : a + "; " + b;
    }

    private ParsedBlock parse(RawBlock raw, int physical, long offset) {
        BlockRef ref = new BlockRef(physical, offset);
        if (raw == null) {
            return ParsedBlock.of(ref, 0, BlockStatus.EMPTY, "NO_BLOCK_AT_PHYSICAL_" + physical);
        }
        byte[] b = raw.bytes();
        if (b.length < JournalFormat.HEADER_SIZE || !JournalFormat.hasMagic(b)) {
            return ParsedBlock.of(ref, 0, BlockStatus.BAD_MAGIC, "sha=" + raw.sha256());
        }
        int type = JournalFormat.u32(b, 4);
        long seq = Integer.toUnsignedLong(JournalFormat.u32(b, 8));
        return switch (type) {
            case JournalFormat.TYPE_DESCRIPTOR -> new ParsedBlock(ref, seq,
                    BlockStatus.DESCRIPTOR, JournalFormat.parseTags(b), List.of(), 0, new byte[0], null);
            case JournalFormat.TYPE_COMMIT -> new ParsedBlock(ref, seq,
                    BlockStatus.COMMIT, List.of(), List.of(), JournalFormat.commitChecksum(b), new byte[0], null);
            case JournalFormat.TYPE_REVOKE -> new ParsedBlock(ref, seq,
                    BlockStatus.REVOKE, List.of(), JournalFormat.parseRevoked(b), 0, new byte[0], null);
            case JournalFormat.TYPE_DATA -> {
                byte[] payload = new byte[b.length - JournalFormat.HEADER_SIZE];
                System.arraycopy(b, JournalFormat.HEADER_SIZE, payload, 0, payload.length);
                yield new ParsedBlock(ref, seq, BlockStatus.DATA, List.of(), List.of(), 0, payload, null);
            }
            default -> ParsedBlock.of(ref, seq, BlockStatus.UNKNOWN_TYPE, "type=" + type);
        };
    }

    private static final class MutableGroup {
        final long sequence;
        final List<BlockRef> members = new ArrayList<>();
        final List<BlockRef> revokes = new ArrayList<>();
        final List<BlockRef> data = new ArrayList<>();
        final List<Long> revokedBlocks = new ArrayList<>();
        final List<JournalFormat.Tag> tags = new ArrayList<>();
        final List<Long> expectedData = new ArrayList<>();
        BlockRef descriptor;
        BlockRef commit;
        boolean committed;
        String note;

        MutableGroup(long sequence) {
            this.sequence = sequence;
        }

        MutableGroup close(int version, JournalImage image) {
            return close(version, image, 0);
        }

        MutableGroup close(int version, JournalImage image, int storedChecksum) {
            this.storedChecksum = storedChecksum;
            this.version = version;
            this.image = image;
            return this;
        }

        int storedChecksum;
        int version;
        JournalImage image;

        TransactionGroup build() {
            Map<Long, List<BlockRef>> covers = new LinkedHashMap<>();
            int tagIdx = 0;
            for (BlockRef d : data) {
                while (tagIdx < tags.size() && tags.get(tagIdx).deleted()) {
                    tagIdx++;
                }
                if (tagIdx < tags.size()) {
                    long fs = tags.get(tagIdx).fsBlock();
                    covers.computeIfAbsent(fs, k -> new ArrayList<>()).add(d);
                    tagIdx++;
                }
            }
            Boolean csumOk = null;
            if (committed && storedChecksum != 0 && version >= JournalFormat.CSUM_V2) {
                List<byte[]> parts = new ArrayList<>();
                for (BlockRef m : members) {
                    if (!m.equals(commit)) {
                        RawBlock rb = image.at(m.physical());
                        if (rb != null) {
                            parts.add(rb.bytes());
                        }
                    }
                }
                csumOk = JournalFormat.transactionChecksum(parts) == storedChecksum;
                if (Boolean.FALSE.equals(csumOk)) {
                    note = append(note, "CHECKSUM_MISMATCH stored="
                            + Integer.toUnsignedString(storedChecksum)
                            + " computed=" + Integer.toUnsignedString(JournalFormat.transactionChecksum(parts)));
                }
            }
            return new TransactionGroup(sequence, List.copyOf(members), descriptor,
                    List.copyOf(revokes), List.copyOf(data), commit, committed, csumOk,
                    covers, List.copyOf(revokedBlocks), note);
        }
    }
}

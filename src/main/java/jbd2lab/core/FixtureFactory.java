package jbd2lab.core;

import jbd2lab.model.Domain.*;

import java.util.HexFormat;
import java.util.List;
import java.util.Map;

public final class FixtureFactory {
    public static final String CASE_ID = "ext4-jbd2-lab-offline-case";
    public static final int BLOCK_SIZE = 1024;
    public static final int RING_CAPACITY = 32;
    public static final int RING_ORIGIN = 12;
    public static final String CHECKSUM_VERSION = "jbd2-crc32c-v1";

    private FixtureFactory() {
    }

    public static Snapshot initialCase() {
        return snapshot(11, "v1", "half-tail-initial");
    }

    public static Snapshot afterParameterRevision() {
        return snapshot(23, "v2", "parameter-crash-point-moved-past-checksum");
    }

    public static Snapshot afterLocalSourceChange(Snapshot base) {
        List<RawBlock> blocks = base.blocks().stream()
                .map(block -> "journal-slot-004".equals(block.id())
                        ? new RawBlock(block.id(), block.role(), block.ringSlot(), block.filesystemBlock(),
                        encodeData(0xffffff00L, "A2-local-payload-revision"), block.sourceUri(),
                        withAnnotation(block, "lab.sourceRevision", "v3-local-dependency-only"))
                        : block)
                .toList();
        return new Snapshot(base.id(), base.name(), base.superblock(), blocks, base.probes(), base.crashPoint());
    }

    public static Snapshot snapshot(int crashThroughSlot, String revision, String crashLabel) {
        return new Snapshot(
                CASE_ID,
                "ext4 日志演算室：事务描述块、revoke 与崩溃点回放",
                superblock(),
                List.of(
                        raw(0, DESCRIPTOR, 0xfffffffeL, Jbd2Codec.entry(Domain.JBD2_MAGIC, DESCRIPTOR, 0xfffffffeL,
                                Jbd2Codec.descriptorPayload(new long[]{100L, 101L}))),
                        raw(1, DATA, 100L, encodeData(0xfffffffeL, 100L, "A")),
                        raw(2, DATA, 101L, encodeData(0xfffffffeL, 101L, "A")),
                        raw(3, COMMIT, null, commit(0xfffffffeL)),

                        raw(4, DESCRIPTOR, 0xffffff00L, Jbd2Codec.entry(Domain.JBD2_MAGIC, DESCRIPTOR, 0xffffff00L,
                                Jbd2Codec.descriptorPayload(new long[]{200L, 200L}))),
                        raw(5, DATA, 200L, encodeData(0xffffff00L, 200L, "B-first-duplicate")),
                        raw(6, DATA, 200L, encodeData(0xffffff00L, 200L, "B-second-duplicate-wins")),
                        raw(7, COMMIT, null, commit(0xffffff00L)),

                        raw(8, DESCRIPTOR, 0xffffff01L, Jbd2Codec.entry(Domain.JBD2_MAGIC, DESCRIPTOR, 0xffffff01L,
                                Jbd2Codec.descriptorPayload(new long[]{101L}))),
                        raw(9, DATA, 101L, encodeData(0xffffff01L, 101L, "C-write-before-revoke")),
                        raw(10, COMMIT, null, commit(0xffffff01L)),
                        raw(11, REVOKE, null, revoke(0xffffff02L, 101L)),
                        raw(12, COMMIT, null, commit(0xffffff02L)),

                        raw(13, DESCRIPTOR, 0xffffff03L, Jbd2Codec.entry(Domain.JBD2_MAGIC, DESCRIPTOR, 0xffffff03L,
                                Jbd2Codec.descriptorPayload(new long[]{101L, 303L}))),
                        raw(14, DATA, 101L, encodeData(0xffffff03L, 101L, "D-rewrite-after-revoke-wins")),
                        raw(15, DATA, 303L, encodeData(0xffffff03L, 303L, "D-new-target")),
                        raw(16, COMMIT, null, commit(0xffffff03L)),

                        badChecksum(17, DESCRIPTOR, 0xffffff04L, Jbd2Codec.entry(Domain.JBD2_MAGIC, DESCRIPTOR, 0xffffff04L,
                                Jbd2Codec.descriptorPayload(new long[]{404L}))),
                        raw(18, DATA, 404L, encodeData(0xffffff04L, 404L, "E-after-corrupt-boundary")),
                        raw(19, COMMIT, null, commit(0xffffff04L)),

                        raw(20, DESCRIPTOR, 0xffffff05L, Jbd2Codec.entry(Domain.JBD2_MAGIC, DESCRIPTOR, 0xffffff05L,
                                Jbd2Codec.descriptorPayload(new long[]{505L}))),
                        raw(21, DATA, 505L, encodeData(0xffffff05L, 505L, "F-independent-later-commit")),
                        raw(22, COMMIT, null, commit(0xffffff05L)),

                        raw(23, DESCRIPTOR, 0xffffff06L, Jbd2Codec.entry(Domain.JBD2_MAGIC, DESCRIPTOR, 0xffffff06L,
                                Jbd2Codec.descriptorPayload(new long[]{606L, 607L}))),
                        raw(24, DATA, 606L, encodeData(0xffffff06L, 606L, "G-half-tail-after-crash")),
                        raw(25, DATA, 607L, encodeData(0xffffff06L, 607L, "G-half-tail-missing-commit"))
                ),
                List.of(invalidBlockSizeProbe()),
                new CrashPoint("crash-" + revision, crashThroughSlot,
                        "journal-ring-slot:" + crashThroughSlot + ":inclusive-byte-end",
                        "journal-block",
                        "ring origin physical block " + RING_ORIGIN + "; capacity " + RING_CAPACITY
                                + "; " + BLOCK_SIZE + " bytes/slot")
        );
    }

    private static SuperblockMaterial superblock() {
        byte[] bytes = new byte[BLOCK_SIZE];
        bytes[0] = 0x53;
        bytes[1] = (byte) 0xef;
        putBe32(bytes, 24, BLOCK_SIZE);
        putBe32(bytes, 28, RING_ORIGIN);
        bytes[32] = 1;
        return new SuperblockMaterial(
                "ext4-superblock-fixture-v1",
                HexFormat.of().formatHex(bytes),
                Quantity.parsed("1024 B", "byte", BLOCK_SIZE, "superblock.s_log_block_size: 1024 << s_log_block_size"),
                RING_ORIGIN,
                "crc32c",
                CHECKSUM_VERSION,
                List.of()
        );
    }

    private static RawBlock invalidBlockSizeProbe() {
        byte[] bytes = new byte[512];
        bytes[0] = 0x53;
        bytes[1] = (byte) 0xef;
        putBe32(bytes, 24, 512);
        return new RawBlock(
                "probe-invalid-block-size",
                "invalid-block-size",
                null,
                null,
                HexFormat.of().formatHex(bytes),
                "fixture://ext4-journal-lab/superblocks/probe-invalid-512.bytes",
                Map.of(
                        "lab.representation", "512 B",
                        "lab.coordinateBasis", "probe copied from non-ext4 small-block volume",
                        "lab.expectedDiagnostic", "EXT4_INVALID_BLOCK_SIZE_PROBE"
                )
        );
    }

    private static RawBlock raw(int slot, int type, Long target, byte[] bytes) {
        String role = switch (type) {
            case DESCRIPTOR -> "jbd2-descriptor";
            case COMMIT -> "jbd2-commit";
            case REVOKE -> "jbd2-revoke";
            case DATA -> "jbd2-data";
            default -> "jbd2-block";
        };
        return new RawBlock(
                "journal-slot-" + String.format("%03d", slot),
                role,
                slot,
                target,
                HexFormat.of().formatHex(bytes),
                "fixture://ext4-journal-lab/ring/capacity-32/slot-" + String.format("%03d", slot) + ".bin",
                Map.of(
                        "lab.ringOrigin", "journal-start-fsblk-" + RING_ORIGIN,
                        "lab.ringCapacity", String.valueOf(RING_CAPACITY),
                        "lab.checksumVersion", CHECKSUM_VERSION
                )
        );
    }

    private static RawBlock badChecksum(int slot, int type, long target, byte[] validBytes) {
        byte[] bad = validBytes.clone();
        bad[bad.length - 1] ^= 0xff;
        return new RawBlock(
                "journal-slot-" + String.format("%03d", slot),
                "jbd2-descriptor-corrupt",
                slot,
                target,
                HexFormat.of().formatHex(bad),
                "fixture://ext4-journal-lab/ring/capacity-32/slot-" + String.format("%03d", slot) + ".bin",
                Map.of(
                        "lab.mutation", "checksum-tail-only",
                        "lab.ringOrigin", "journal-start-fsblk-" + RING_ORIGIN,
                        "lab.ringCapacity", String.valueOf(RING_CAPACITY),
                        "lab.checksumVersion", CHECKSUM_VERSION
                )
        );
    }

    private static byte[] encodeData(long sequence, long target, String marker) {
        return Jbd2Codec.entry(Domain.JBD2_MAGIC, DATA, sequence,
                Jbd2Codec.dataPayload(target, marker, BLOCK_SIZE));
    }

    private static byte[] commit(long sequence) {
        return Jbd2Codec.entry(Domain.JBD2_MAGIC, COMMIT, sequence, new byte[]{1, 2, 3, 4});
    }

    private static byte[] revoke(long sequence, long target) {
        return Jbd2Codec.entry(Domain.JBD2_MAGIC, REVOKE, sequence,
                Jbd2Codec.revokePayload(new long[]{target}));
    }

    private static void putBe32(byte[] target, int offset, int value) {
        target[offset] = (byte) (value >>> 24);
        target[offset + 1] = (byte) (value >>> 16);
        target[offset + 2] = (byte) (value >>> 8);
        target[offset + 3] = (byte) value;
    }

    private static Map<String, String> withAnnotation(RawBlock block, String key, String value) {
        var map = new java.util.HashMap<>(block.annotations());
        map.put(key, value);
        return Map.copyOf(map);
    }
}

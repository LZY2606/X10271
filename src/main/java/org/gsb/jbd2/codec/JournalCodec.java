package org.gsb.jbd2.codec;

import org.gsb.jbd2.model.Domain.BlockKind;
import org.gsb.jbd2.model.Domain.ChecksumKind;
import org.gsb.jbd2.model.Domain.DescriptorEntry;
import org.gsb.jbd2.model.Domain.JournalBlock;
import org.gsb.jbd2.model.Domain.RevokeEntry;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.zip.CRC32;

public final class JournalCodec {
    public static final int LAB_BLOCK_SIZE = 512;
    public static final int MAGIC = 0x4A424432;
    public static final int HEADER_SIZE = 32;
    public static final int TAG_SIZE = 16;
    public static final int REVOKE_ENTRY_SIZE = 24;
    private static final int TYPE_DESCRIPTOR = 1;
    private static final int TYPE_COMMIT = 2;
    private static final int TYPE_REVOKE = 3;
    private static final int TYPE_DATA = 0;

    private JournalCodec() {}

    public static byte[] descriptor(long sequence, ChecksumKind kind, DescriptorEntry... entries) {
        byte[] payload = new byte[entries.length * TAG_SIZE];
        ByteBuffer buffer = wrap(payload);
        for (int index = 0; index < entries.length; index++) {
            DescriptorEntry entry = entries[index];
            buffer.putLong(index * TAG_SIZE, entry.targetBlock());
            buffer.putLong(index * TAG_SIZE + 8, entry.dataPhysicalIndex());
        }
        return block(TYPE_DESCRIPTOR, sequence, kind, payload);
    }

    public static byte[] data(long sequence, long targetBlock, byte... payloadBytes) {
        byte[] payload = new byte[LAB_BLOCK_SIZE - HEADER_SIZE];
        System.arraycopy(payloadBytes, 0, payload, 0, Math.min(payloadBytes.length, payload.length));
        ByteBuffer buffer = wrap(payload);
        buffer.putLong(0, targetBlock);
        byte[] raw = block(TYPE_DATA, sequence, ChecksumKind.NONE, payload);
        wrap(raw).putInt(28, 0);
        return raw;
    }

    public static byte[] commit(long sequence, ChecksumKind kind) {
        return block(TYPE_COMMIT, sequence, kind, new byte[0]);
    }

    public static byte[] revoke(long sequence, ChecksumKind kind, RevokeEntry... entries) {
        byte[] payload = new byte[entries.length * REVOKE_ENTRY_SIZE];
        ByteBuffer buffer = wrap(payload);
        for (int index = 0; index < entries.length; index++) {
            RevokeEntry entry = entries[index];
            int offset = index * REVOKE_ENTRY_SIZE;
            buffer.putLong(offset, entry.targetBlock());
            buffer.putLong(offset + 8, entry.fromSequence());
            buffer.putLong(offset + 16, entry.toSequence());
        }
        return block(TYPE_REVOKE, sequence, kind, payload);
    }

    public static byte[] corruptChecksum(byte[] raw) {
        byte[] copy = raw.clone();
        ByteBuffer buffer = wrap(copy);
        long stored = buffer.getLong(24);
        buffer.putLong(24, stored ^ 0x5A5A5A5A5A5A5A5AL);
        return copy;
    }

    public static JournalBlock parse(long physicalIndex, byte[] raw) {
        if (raw == null || raw.length != LAB_BLOCK_SIZE) {
            return new JournalBlock(physicalIndex, BlockKind.UNKNOWN, 0, null, false,
                    null, null, new byte[0], raw == null ? new byte[0] : raw.clone(),
                    "block length is not 512 B");
        }
        ByteBuffer buffer = wrap(raw);
        int magic = buffer.getInt(0);
        if (magic != MAGIC) {
            return new JournalBlock(physicalIndex, BlockKind.UNKNOWN, 0, null, false,
                    null, null, new byte[0], raw.clone(), "JBD2 laboratory magic is absent");
        }
        int type = buffer.getInt(4);
        long sequence = buffer.getLong(8);
        int payloadLength = buffer.getInt(16);
        int checksumVersion = buffer.getInt(20);
        long storedChecksum = buffer.getLong(24);
        if (payloadLength < 0 || payloadLength > raw.length - HEADER_SIZE) {
            return unknown(physicalIndex, sequence, checksumVersion, raw, "payload length escapes block");
        }
        BlockKind kind = switch (type) {
            case TYPE_DESCRIPTOR -> BlockKind.DESCRIPTOR;
            case TYPE_COMMIT -> BlockKind.COMMIT;
            case TYPE_REVOKE -> BlockKind.REVOKE;
            case TYPE_DATA -> BlockKind.DATA;
            default -> BlockKind.UNKNOWN;
        };
        if (kind == BlockKind.UNKNOWN) {
            return unknown(physicalIndex, sequence, checksumVersion, raw, "unknown block type " + type);
        }
        byte[] payload = new byte[payloadLength];
        System.arraycopy(raw, HEADER_SIZE, payload, 0, payloadLength);
        boolean checksumValid = checksumValid(raw, kind, checksumVersion, storedChecksum);
        Long targetBlock = kind == BlockKind.DATA ? buffer.getLong(HEADER_SIZE) : null;
        JournalBlock block = new JournalBlock(physicalIndex, kind, sequence, checksumVersion, checksumValid,
                targetBlock, null, payload, raw.clone(), checksumValid ? null : "checksum mismatch");
        if (kind == BlockKind.DESCRIPTOR) return parseDescriptor(block, payload);
        if (kind == BlockKind.REVOKE) return parseRevoke(block, payload);
        return block;
    }

    private static JournalBlock parseDescriptor(JournalBlock block, byte[] payload) {
        if (payload.length % TAG_SIZE != 0) {
            return block.withChecksumInvalid("descriptor tag table is not 16 B aligned");
        }
        return block;
    }

    private static JournalBlock parseRevoke(JournalBlock block, byte[] payload) {
        if (payload.length % REVOKE_ENTRY_SIZE != 0) {
            return block.withChecksumInvalid("revoke range table is not 24 B aligned");
        }
        return block;
    }

    public static java.util.List<DescriptorEntry> descriptorEntries(JournalBlock block) {
        java.util.List<DescriptorEntry> entries = new ArrayList<>();
        ByteBuffer buffer = wrap(block.payload());
        for (int offset = 0; offset + TAG_SIZE <= block.payload().length; offset += TAG_SIZE) {
            entries.add(new DescriptorEntry(buffer.getLong(offset), buffer.getLong(offset + 8), entries.size()));
        }
        return entries;
    }

    public static java.util.List<RevokeEntry> revokeEntries(JournalBlock block) {
        java.util.List<RevokeEntry> entries = new ArrayList<>();
        ByteBuffer buffer = wrap(block.payload());
        for (int offset = 0; offset + REVOKE_ENTRY_SIZE <= block.payload().length; offset += REVOKE_ENTRY_SIZE) {
            entries.add(new RevokeEntry(buffer.getLong(offset), buffer.getLong(offset + 8),
                    buffer.getLong(offset + 16), entries.size()));
        }
        return entries;
    }

    private static byte[] block(int type, long sequence, ChecksumKind checksumKind, byte[] payload) {
        byte[] raw = new byte[LAB_BLOCK_SIZE];
        ByteBuffer buffer = wrap(raw);
        buffer.putInt(0, MAGIC);
        buffer.putInt(4, type);
        buffer.putLong(8, sequence & 0xFFFFFFFFL);
        buffer.putInt(16, payload.length);
        buffer.putInt(20, checksumKind.versionCode());
        System.arraycopy(payload, 0, raw, HEADER_SIZE, payload.length);
        if (checksumKind != ChecksumKind.NONE) {
            buffer.putLong(24, checksum(raw, checksumKind));
        }
        return raw;
    }

    private static boolean checksumValid(byte[] raw, BlockKind kind, int version, long stored) {
        ChecksumKind checksumKind = switch (version) {
            case 0 -> ChecksumKind.NONE;
            case 1, 2 -> ChecksumKind.CRC32C;
            default -> null;
        };
        if (checksumKind == null) return false;
        if (checksumKind == ChecksumKind.NONE) return stored == 0;
        if (kind == BlockKind.DATA) return true;
        return checksum(raw, checksumKind) == stored;
    }

    private static long checksum(byte[] raw, ChecksumKind kind) {
        int payloadLength = wrap(raw).getInt(16);
        if (kind == ChecksumKind.CRC32C) {
            Crc32C crc = new Crc32C();
            crc.update(raw, 0, 24);
            crc.update(raw, HEADER_SIZE, payloadLength);
            return crc.getValue();
        }
        CRC32 crc = new CRC32();
        crc.update(raw, 0, 24);
        crc.update(raw, HEADER_SIZE, payloadLength);
        return crc.getValue();
    }

    private static JournalBlock unknown(long physicalIndex, long sequence, int version, byte[] raw, String reason) {
        return new JournalBlock(physicalIndex, BlockKind.UNKNOWN, sequence & 0xFFFFFFFFL,
                version, false, null, null, new byte[0], raw.clone(), reason);
    }

    private static ByteBuffer wrap(byte[] bytes) {
        return ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
    }
}

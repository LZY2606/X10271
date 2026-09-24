package jbd2lab.core;

import jbd2lab.model.Domain.DescriptorTag;
import jbd2lab.model.Domain.JournalBlock;
import jbd2lab.model.Domain.RawBlock;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.zip.CRC32C;

public final class Jbd2Codec {
    private Jbd2Codec() {
    }

    public static int u32(byte[] bytes, int offset) {
        return ByteBuffer.wrap(bytes, offset, 4).order(ByteOrder.BIG_ENDIAN).getInt();
    }

    public static long unsignedU32(byte[] bytes, int offset) {
        return Integer.toUnsignedLong(u32(bytes, offset));
    }

    public static byte[] entry(long magic, int type, long sequence, byte[] payload) {
        if (payload.length + Domain.HEADER_SIZE + Domain.CHECKSUM_SIZE > 65536) {
            throw new IllegalArgumentException("journal block exceeds encodable fixture size");
        }
        byte[] out = new byte[payload.length + Domain.HEADER_SIZE + Domain.CHECKSUM_SIZE];
        ByteBuffer buffer = ByteBuffer.wrap(out).order(ByteOrder.BIG_ENDIAN);
        buffer.putInt((int) magic);
        buffer.putInt(type);
        buffer.putInt((int) sequence);
        buffer.putInt(0);
        buffer.put(payload);
        int checksum = checksum(out, out.length - Domain.CHECKSUM_SIZE);
        buffer.putInt(checksum);
        return out;
    }

    public static byte[] descriptorPayload(long[] targets) {
        ByteBuffer buffer = ByteBuffer.allocate(targets.length * 8);
        buffer.order(ByteOrder.BIG_ENDIAN);
        for (long target : targets) {
            buffer.putInt((int) target);
            buffer.putInt(0);
        }
        return buffer.array();
    }

    public static byte[] revokePayload(long[] targets) {
        ByteBuffer buffer = ByteBuffer.allocate(targets.length * 4);
        buffer.order(ByteOrder.BIG_ENDIAN);
        for (long target : targets) {
            buffer.putInt((int) target);
        }
        return buffer.array();
    }

    public static byte[] dataPayload(long target, String marker, int blockSize) {
        String text = "fsblk=" + target + "|" + marker;
        byte[] body = text.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] payload = new byte[blockSize - Domain.HEADER_SIZE - Domain.CHECKSUM_SIZE];
        System.arraycopy(body, 0, payload, 0, Math.min(body.length, payload.length));
        return payload;
    }

    public static int checksum(byte[] block, int length) {
        CRC32C crc = new CRC32C();
        crc.update(block, 0, length);
        return (int) crc.getValue();
    }

    public static int checksum(byte[] block) {
        return checksum(block, block.length - Domain.CHECKSUM_SIZE);
    }

    public static JournalBlock decode(RawBlock raw) {
        byte[] block = raw.bytes();
        List<String> diagnostics = new ArrayList<>();
        if (block.length < Domain.HEADER_SIZE + Domain.CHECKSUM_SIZE) {
            diagnostics.add("JBD2_BLOCK_TOO_SHORT:raw=" + raw.id());
        }
        long magic = block.length >= 4 ? unsignedU32(block, 0) : 0;
        int type = block.length >= 8 ? u32(block, 4) : -1;
        long sequence = block.length >= 12 ? unsignedU32(block, 8) : 0;
        int stored = block.length >= Domain.CHECKSUM_SIZE
                ? u32(block, block.length - Domain.CHECKSUM_SIZE)
                : 0;
        int calculated = block.length >= Domain.CHECKSUM_SIZE ? checksum(block) : 0;
        boolean magicValid = magic == Integer.toUnsignedLong(Domain.JBD2_MAGIC);
        boolean checksumValid = stored == calculated;
        if (!magicValid) {
            diagnostics.add("JBD2_BAD_MAGIC:raw=" + raw.id());
        }
        if (!checksumValid) {
            diagnostics.add("JBD2_BAD_CHECKSUM:raw=" + raw.id()
                    + ";stored=0x" + HexFormat.of().toHexDigits(stored, 8)
                    + ";calculated=0x" + HexFormat.of().toHexDigits(calculated, 8));
        }

        int payloadLength = Math.max(0, block.length - Domain.HEADER_SIZE - Domain.CHECKSUM_SIZE);
        byte[] payload = new byte[payloadLength];
        System.arraycopy(block, Domain.HEADER_SIZE, payload, 0, payloadLength);
        List<DescriptorTag> tags = new ArrayList<>();
        List<Long> revokedBlocks = new ArrayList<>();
        if (type == Domain.DESCRIPTOR) {
            for (int offset = 0; offset + 8 <= payload.length; offset += 8) {
                long target = Integer.toUnsignedLong(u32(payload, offset));
                long flags = Integer.toUnsignedLong(u32(payload, offset + 4));
                if (target == 0 && flags == 0 && offset > 0) {
                    continue;
                }
                tags.add(new DescriptorTag(target, flags, Domain.HEADER_SIZE + offset));
            }
        } else if (type == Domain.REVOKE) {
            for (int offset = 0; offset + 4 <= payload.length; offset += 4) {
                long target = Integer.toUnsignedLong(u32(payload, offset));
                if (target != 0) {
                    revokedBlocks.add(target);
                }
            }
        } else if (type != Domain.COMMIT && type != Domain.DATA && type > 0) {
            diagnostics.add("JBD2_UNKNOWN_BLOCK_TYPE:raw=" + raw.id() + ";type=" + type);
        }

        return new JournalBlock(
                raw.id(),
                raw.ringSlot(),
                type,
                sequence,
                stored,
                calculated,
                checksumValid,
                magicValid,
                payload,
                List.copyOf(tags),
                List.copyOf(revokedBlocks),
                List.copyOf(diagnostics)
        );
    }
}

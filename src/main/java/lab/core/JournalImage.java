package lab.core;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 离线案例使用的 JBD2 风格块镜像格式（每个块独立头 + 尾校验）：
 *
 *   [0..3]   magic "JBD2"
 *   [4..7]   block type (BE32)
 *   [8..11]  transaction sequence (BE32, 无符号、可回绕)
 *   [12..15] body length (BE32)
 *   [16..]   body
 *   末尾4字节 checksum（对 [0, blockSize-4) 计算；版本取自 superblock）
 *
 * DESCRIPTOR body: tagCount(BE32) + [fsBlock BE64, flags BE32]*
 * REVOKE body:     count(BE32) + [fsBlock BE64]*
 * COMMIT body:     空
 * DATA body:       文件系统数据字节（fsBlock 由描述块标签按环顺序给出）
 */
public final class JournalImage {
    public static final int MAGIC = 0x4A424432;
    public static final int HEADER_LEN = 16;

    private JournalImage() {}

    public static byte[] buildBlock(BlockType type, long sequence, byte[] body, int blockSize, String checksumVersion) {
        byte[] blk = new byte[blockSize];
        ByteBuffer bb = ByteBuffer.wrap(blk).order(ByteOrder.BIG_ENDIAN);
        bb.putInt(MAGIC);
        bb.putInt(type.code);
        bb.putInt((int) sequence);
        bb.putInt(body == null ? 0 : body.length);
        if (body != null) bb.put(body);
        long cs = Checksum.compute(checksumVersion, blk, 0, blockSize - 4);
        ByteBuffer.wrap(blk, blockSize - 4, 4).order(ByteOrder.BIG_ENDIAN).putInt((int) cs);
        return blk;
    }

    public static byte[] descriptor(long sequence, List<DescriptorTag> tags, int blockSize, String csVer) {
        ByteBuffer body = ByteBuffer.allocate(4 + tags.size() * 12).order(ByteOrder.BIG_ENDIAN);
        body.putInt(tags.size());
        for (DescriptorTag t : tags) {
            body.putLong(t.fsBlock());
            body.putInt(t.flags());
        }
        return buildBlock(BlockType.DESCRIPTOR, sequence, body.array(), blockSize, csVer);
    }

    public static byte[] commit(long sequence, int blockSize, String csVer) {
        return buildBlock(BlockType.COMMIT, sequence, new byte[0], blockSize, csVer);
    }

    public static byte[] revoke(long sequence, List<Long> blocks, int blockSize, String csVer) {
        ByteBuffer body = ByteBuffer.allocate(4 + blocks.size() * 8).order(ByteOrder.BIG_ENDIAN);
        body.putInt(blocks.size());
        for (Long b : blocks) body.putLong(b);
        return buildBlock(BlockType.REVOKE, sequence, body.array(), blockSize, csVer);
    }

    public static byte[] data(long sequence, byte[] payload, int blockSize, String csVer) {
        byte[] p = payload == null ? new byte[0] : payload;
        byte[] trimmed = p.length > blockSize - HEADER_LEN
                ? java.util.Arrays.copyOf(p, blockSize - HEADER_LEN) : p;
        return buildBlock(BlockType.DATA, sequence, trimmed, blockSize, csVer);
    }

    public static byte[] readFile(Path p) throws IOException {
        return Files.readAllBytes(p);
    }

    /** 扫描单个块（不解析事务结构）。 */
    public static JournalBlock scanBlock(byte[] image, int blockSize, int ringIndex, String csVer) {
        int off = ringIndex * blockSize;
        if (off + blockSize > image.length) {
            throw new IllegalArgumentException("ringIndex " + ringIndex + " 超出镜像长度");
        }
        ByteBuffer bb = ByteBuffer.wrap(image, off, blockSize).order(ByteOrder.BIG_ENDIAN);
        int magic = bb.getInt();
        int typeCode = bb.getInt();
        long sequence = bb.getInt() & 0xffffffffL;
        int bodyLen = bb.getInt();
        byte[] body = new byte[Math.max(0, Math.min(bodyLen, blockSize - HEADER_LEN - 4))];
        bb.get(body);
        long stored = bb.getInt(blockSize - 4) & 0xffffffffL;
        long computed = Checksum.compute(csVer, image, off, blockSize - 4);
        BlockType type = magic == MAGIC ? BlockType.of(typeCode) : BlockType.UNKNOWN;

        List<DescriptorTag> tags = null;
        List<Long> revoked = null;
        if (type == BlockType.DESCRIPTOR && body.length >= 4) {
            ByteBuffer cb = ByteBuffer.wrap(body).order(ByteOrder.BIG_ENDIAN);
            int n = cb.getInt();
            tags = new ArrayList<>();
            for (int i = 0; i < n && cb.remaining() >= 12; i++) {
                tags.add(new DescriptorTag(cb.getLong(), cb.getInt()));
            }
        } else if (type == BlockType.REVOKE && body.length >= 4) {
            ByteBuffer cb = ByteBuffer.wrap(body).order(ByteOrder.BIG_ENDIAN);
            int n = cb.getInt();
            revoked = new ArrayList<>();
            for (int i = 0; i < n && cb.remaining() >= 8; i++) revoked.add(cb.getLong());
        }
        return new JournalBlock(ringIndex, type, sequence, tags, revoked, body,
                stored, stored == computed && magic == MAGIC, csVer);
    }

    /** 顺序扫描整环。 */
    public static List<JournalBlock> scanAll(byte[] image, int blockSize, String csVer) {
        int count = image.length / blockSize;
        List<JournalBlock> blocks = new ArrayList<>(count);
        for (int i = 0; i < count; i++) blocks.add(scanBlock(image, blockSize, i, csVer));
        return blocks;
    }
}

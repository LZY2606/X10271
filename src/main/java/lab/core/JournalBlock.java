package lab.core;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lab.core.JsonUtil.JN;

import java.util.List;

/**
 * 日志环中的一个块。ringIndex 是相对环起点的物理坐标（0 起），
 * sequence 是 32 位事务序号（可能回绕）。校验状态随块保留。
 */
public record JournalBlock(
        int ringIndex,
        BlockType type,
        long sequence,
        List<DescriptorTag> tags,
        List<Long> revokedBlocks,
        byte[] payload,
        long storedChecksum,
        boolean checksumOk,
        String checksumVersion) {

    public ObjectNode toJson() {
        ObjectNode n = JN.obj();
        n.put("ringIndex", ringIndex);
        n.put("type", type.name());
        n.put("sequence", sequence & 0xffffffffL);
        n.put("checksumVersion", checksumVersion);
        n.put("checksumOk", checksumOk);
        n.put("storedChecksum", storedChecksum);
        if (tags != null) {
            ArrayNode ts = n.putArray("tags");
            for (DescriptorTag t : tags) {
                var tn = JN.obj();
                tn.put("fsBlock", t.fsBlock());
                tn.put("flags", t.flags());
                ts.add(tn);
            }
        }
        if (revokedBlocks != null) {
            ArrayNode rs = n.putArray("revokedBlocks");
            revokedBlocks.forEach(rs::add);
        }
        return n;
    }
}

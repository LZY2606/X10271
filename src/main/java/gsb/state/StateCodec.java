package gsb.state;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import gsb.jbd2.Model;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/** 状态 JSON <-> 领域对象。导入层保留原始表示，派生层才做规范化。 */
public final class StateCodec {
    public static final ObjectMapper M = new ObjectMapper();

    private StateCodec() {}

    public static Model.Superblock superblock(ObjectNode state) {
        JsonNode sb = state.required("superblock");
        String raw = sb.path("blockSizeRaw").asText("");
        String unit = sb.path("blockSizeUnit").asText("bytes");
        Long bytes = null;
        String diag = null;
        try {
            long v = Long.parseLong(raw.trim());
            if (Model.Superblock.validBlockSize(v)) {
                bytes = v;
            } else {
                diag = "非 2 的幂或超出 1024..65536 范围";
            }
        } catch (NumberFormatException e) {
            diag = "无法解析为整数";
        }
        return new Model.Superblock(raw, unit, bytes, diag,
                sb.path("journalStartBlock").asLong(),
                sb.path("firstSequence").asLong(),
                sb.path("checksumVersion").asInt(3),
                sb.path("rawHex").asText(""));
    }

    public static List<String> blocks(ObjectNode state) {
        List<String> out = new ArrayList<>();
        state.withArray("blocks").forEach(n -> out.add(n.asText()));
        return out;
    }

    public static long startOffset(ObjectNode state) {
        return state.path("journalStartOffset").asLong(0);
    }

    public static int crashPoint(ObjectNode state) {
        return state.path("crashPointBlock").asInt(blocks(state).size());
    }

    public static String selectedCandidate(ObjectNode state) {
        return state.path("params").path("selectedCandidate").asText(null);
    }

    public static String sha256(String s) {
        return sha256(s.getBytes(StandardCharsets.UTF_8));
    }

    public static String sha256(byte[] b) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static ObjectNode newObject() {
        return M.createObjectNode();
    }
}

package lab.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

public final class JsonUtil {
    public static final ObjectMapper MAPPER = new ObjectMapper();

    public static final class JN {
        private static final JsonNodeFactory F = JsonNodeFactory.instance;
        public static ObjectNode obj() { return F.objectNode(); }
        public static ArrayNode arr() { return F.arrayNode(); }
    }

    private JsonUtil() {}
}

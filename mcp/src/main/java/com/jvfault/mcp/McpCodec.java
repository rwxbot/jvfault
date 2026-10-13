package com.jvfault.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * MCP JSON-RPC 编解码辅助。
 *
 * @since v0.11.0 (2025)
 * @author jvfault team
 */
final class McpCodec {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private McpCodec() {
    }

    record Request(Object id, String method, JsonNode params) {
    }

    static Request parseRequest(String json) {
        try {
            JsonNode root = MAPPER.readTree(json);
            Object id = root.has("id") && !root.get("id").isNull()
                    ? root.get("id").asText() : null;
            String method = root.path("method").textValue();
            return new Request(id, method, root.path("params"));
        } catch (Exception e) {
            throw new IllegalArgumentException("非法 JSON-RPC 消息", e);
        }
    }

    static String success(Object id, Object result) {
        ObjectNode response = MAPPER.createObjectNode();
        response.put("jsonrpc", "2.0");
        response.put("id", String.valueOf(id));
        response.set("result", MAPPER.valueToTree(result));
        return response.toString();
    }

    static String error(Object id, int code, String message) {
        ObjectNode response = MAPPER.createObjectNode();
        response.put("jsonrpc", "2.0");
        response.put("id", String.valueOf(id));
        ObjectNode err = response.putObject("error");
        err.put("code", code);
        err.put("message", message);
        return response.toString();
    }

    static JsonObjectBuilder jsonObject() {
        return new JsonObjectBuilder();
    }

    /** JSON 对象构建器（链式 put + build） */
    static final class JsonObjectBuilder {
        private final ObjectNode node = JsonNodeFactory.instance.objectNode();

        JsonObjectBuilder put(String key, String value) {
            node.put(key, value);
            return this;
        }

        JsonObjectBuilder put(String key, int value) {
            node.put(key, value);
            return this;
        }

        JsonObjectBuilder put(String key, long value) {
            node.put(key, value);
            return this;
        }

        JsonObjectBuilder put(String key, boolean value) {
            node.put(key, value);
            return this;
        }

        JsonObjectBuilder put(String key, JsonNode value) {
            node.set(key, value);
            return this;
        }

        JsonObjectBuilder put(String key, JsonObjectBuilder value) {
            node.set(key, value.build());
            return this;
        }

        JsonObjectBuilder put(String key, JsonArrayBuilder value) {
            node.set(key, value.build());
            return this;
        }

        ObjectNode build() {
            return node;
        }
    }

    static JsonArrayBuilder jsonArray() {
        return new JsonArrayBuilder();
    }

    static Map<String, Object> toMap(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        node.properties().forEach(e -> out.put(e.getKey(), MAPPER.convertValue(e.getValue(), Object.class)));
        return out;
    }

    static final class JsonArrayBuilder {
        private final com.fasterxml.jackson.databind.node.ArrayNode array =
                JsonNodeFactory.instance.arrayNode();

        JsonArrayBuilder add(JsonNode value) {
            array.add(value);
            return this;
        }

        JsonArrayBuilder add(ObjectNode value) {
            array.add(value);
            return this;
        }

        JsonArrayBuilder add(Object value) {
            array.add(MAPPER.valueToTree(value));
            return this;
        }

        com.fasterxml.jackson.databind.node.ArrayNode build() {
            return array;
        }
    }
}

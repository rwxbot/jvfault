package com.jvfault.transport.kafka;

import com.fasterxml.jackson.databind.JsonNode;
import com.jvfault.microservices.Message;

import java.util.Base64;
import java.util.Iterator;
import java.util.Map;

/**
 * Kafka wire 格式 — 嵌套 JSON 单行载荷（与 redis 相同的 Message 结构）。
 *
 * @since v0.6.0 (2020)
 * @author jvfault team
 */
public class KafkaWire {

    private String id;
    private String pattern;
    private String dataBase64;
    private String correlationId;
    private String replyTopic;
    private String error;
    private Map<String, String> headers;

    public static KafkaWire from(Message message, String replyTopic) {
        KafkaWire wire = new KafkaWire();
        wire.id = message.getId();
        wire.pattern = message.getPattern();
        wire.dataBase64 = Base64.getEncoder().encodeToString(message.getData());
        wire.correlationId = message.getCorrelationId();
        wire.replyTopic = replyTopic;
        Map<String, String> headers = message.getHeaders();
        if (headers != null && !headers.isEmpty()) {
            wire.headers = new java.util.LinkedHashMap<>(headers);
            wire.error = headers.get(Message.HEADER_ERROR);
        }
        return wire;
    }

    /**
     * 将任意 Message 直接序列化为 JSON 字符串（用于 Kafka producer payload）。
     */
    public static String serialize(Message message, com.fasterxml.jackson.databind.ObjectMapper mapper, String replyTopic) {
        try {
            return mapper.writeValueAsString(from(message, replyTopic));
        } catch (Exception e) {
            throw new com.jvfault.microservices.TransportException("Kafka wire 编码失败", e);
        }
    }

    /**
     * 从 Kafka ConsumerRecord 反序列化（自动选顶层 JSON key）。
     */
    public static Message deserialize(String body, com.fasterxml.jackson.databind.ObjectMapper mapper) {
        try {
            JsonNode root = mapper.readTree(body);
            String pattern = root.path("pattern").textValue();
            String dataB64 = root.path("dataBase64").textValue();
            byte[] data = dataB64 != null ? Base64.getDecoder().decode(dataB64) : new byte[0];
            String correlationId = root.path("correlationId").textValue();
            Map<String, String> headers = new java.util.LinkedHashMap<>();
            JsonNode headersNode = root.path("headers");
            if (headersNode.isObject()) {
                Iterator<Map.Entry<String, JsonNode>> it = headersNode.fields();
                while (it.hasNext()) {
                    Map.Entry<String, JsonNode> e = it.next();
                    headers.put(e.getKey(), e.getValue().asText());
                }
            }
            return new Message(pattern, data, correlationId, headers);
        } catch (Exception e) {
            throw new com.jvfault.microservices.TransportException("Kafka wire 解码失败", e);
        }
    }

    public String getPattern() { return pattern; }
    public void setPattern(String pattern) { this.pattern = pattern; }
    public String getDataBase64() { return dataBase64; }
    public void setDataBase64(String dataBase64) { this.dataBase64 = dataBase64; }
    public String getCorrelationId() { return correlationId; }
    public void setCorrelationId(String correlationId) { this.correlationId = correlationId; }
    public String getReplyTopic() { return replyTopic; }
    public void setReplyTopic(String replyTopic) { this.replyTopic = replyTopic; }
    public String getError() { return error; }
    public void setError(String error) { this.error = error; }
    public Map<String, String> getHeaders() { return headers; }
    public void setHeaders(Map<String, String> headers) { this.headers = headers; }
}

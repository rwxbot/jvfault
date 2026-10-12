package com.jvfault.transport.tcp;

import com.jvfault.microservices.Message;

import java.util.Base64;
import java.util.Map;

/**
 * TCP wire 格式 DTO（Jackson 可反序列化）。
 *
 * @since v0.6.0 (2020)
 * @author jvfault team
 */
public class TcpWireMessage {

    /** 最大帧 8MB，防恶意长度 */
    public static final int MAX_FRAME = 8 * 1024 * 1024;

    private String id;
    private String pattern;
    private String dataBase64;
    private String correlationId;
    private Map<String, String> headers;

    public TcpWireMessage() {
    }

    public static TcpWireMessage from(Message message) {
        TcpWireMessage wire = new TcpWireMessage();
        wire.id = message.getId();
        wire.pattern = message.getPattern();
        wire.dataBase64 = Base64.getEncoder().encodeToString(message.getData());
        wire.correlationId = message.getCorrelationId();
        wire.headers = message.getHeaders().isEmpty() ? null : message.getHeaders();
        return wire;
    }

    public Message toMessage() {
        byte[] data = dataBase64 != null ? Base64.getDecoder().decode(dataBase64) : new byte[0];
        return new Message(pattern, data, correlationId, headers);
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getPattern() { return pattern; }
    public void setPattern(String pattern) { this.pattern = pattern; }
    public String getDataBase64() { return dataBase64; }
    public void setDataBase64(String dataBase64) { this.dataBase64 = dataBase64; }
    public String getCorrelationId() { return correlationId; }
    public void setCorrelationId(String correlationId) { this.correlationId = correlationId; }
    public Map<String, String> getHeaders() { return headers; }
    public void setHeaders(Map<String, String> headers) { this.headers = headers; }
}

package com.jvfault.transport.tcp;

/**
 * 帧协议：4 字节大端长度 + JSON 载荷。
 *
 * @since v0.6.0 (2020)
 * @author jvfault team
 */
public final class TcpMessageFraming {

    private TcpMessageFraming() {
    }

    /** 最大帧 8MB，防恶意长度 */
    public static final int MAX_FRAME = 8 * 1024 * 1024;

    public static byte[] frame(byte[] payload) {
        byte[] out = new byte[4 + payload.length];
        out[0] = (byte) (payload.length >>> 24);
        out[1] = (byte) (payload.length >>> 16);
        out[2] = (byte) (payload.length >>> 8);
        out[3] = (byte) payload.length;
        System.arraycopy(payload, 0, out, 4, payload.length);
        return out;
    }

    /** 从缓冲读取一帧；不完整返回 null（返回剩余字节数供流式处理） */
    public static byte[] tryUnframe(java.io.ByteArrayInputStream in) {
        if (in.available() < 4) {
            return null;
        }
        int len = 0;
        for (int i = 0; i < 4; i++) {
            len = (len << 8) | (in.read() & 0xFF);
        }
        if (len > MAX_FRAME) {
            throw new com.jvfault.microservices.TransportException("帧超限: " + len);
        }
        if (in.available() < len) {
            return null;
        }
        byte[] payload = new byte[len];
        try {
            int read = in.read(payload);
            if (read != len) {
                return null;
            }
        } catch (java.io.IOException e) {
            return null;
        }
        return payload;
    }
}

package com.jvfault.transport.tcp;

import com.jvfault.microservices.Message;
import com.jvfault.microservices.MessageCodec;
import com.jvfault.microservices.JacksonMessageCodec;
import com.jvfault.microservices.TransportClient;
import com.jvfault.microservices.TransportException;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.Socket;

/**
 * TCP 传输客户端（阻塞 IO，单连接）。
 *
 * @since v0.6.0 (2020)
 * @author jvfault team
 */
public class TcpTransportClient implements TransportClient {

    private final String host;
    private final int port;
    private final MessageCodec codec = new JacksonMessageCodec();
    private Socket socket;
    private DataOutputStream out;
    private DataInputStream in;

    public TcpTransportClient(String host, int port) {
        this.host = host;
        this.port = port;
    }

    @Override
    public synchronized void connect() {
        if (socket != null && !socket.isClosed()) {
            return;
        }
        try {
            socket = new Socket(host, port);
            out = new DataOutputStream(socket.getOutputStream());
            in = new DataInputStream(socket.getInputStream());
        } catch (IOException e) {
            throw new TransportException("TCP 连接失败: " + host + ":" + port, e);
        }
    }

    @Override
    public synchronized Message request(String pattern, Object payload, long timeoutMillis) {
        connect();
        try {
            byte[] body = codec.encode(TcpWireMessage.from(new Message(pattern, codec.encode(payload))));
            out.writeInt(body.length);
            out.write(body);
            out.flush();

            socket.setSoTimeout((int) Math.max(timeoutMillis, 1));
            int len = in.readInt();
            byte[] respBytes = new byte[len];
            in.readFully(respBytes);
            Message response = codec.decode(respBytes, TcpWireMessage.class).toMessage();
            if (response.getHeader(Message.HEADER_ERROR) != null) {
                throw new TransportException("远端错误: " + response.getHeader(Message.HEADER_ERROR));
            }
            return response;
        } catch (TransportException e) {
            throw e;
        } catch (Exception e) {
            throw new TransportException("TCP 请求失败: " + pattern, e);
        }
    }

    @Override
    public synchronized void emit(String pattern, Object payload) {
        request(pattern, payload, 10_000);
    }

    @Override
    public synchronized void close() {
        try {
            if (socket != null) {
                socket.close();
            }
        } catch (IOException ignored) {
            // close() 语义是「尽力释放」：socket 已断开时 close 会抛 IOException，
            // 此时没有可恢复动作，也不该让调用方的资源清理链路中断。
        }
    }
}

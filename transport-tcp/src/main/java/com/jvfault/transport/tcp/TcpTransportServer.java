package com.jvfault.transport.tcp;

import com.jvfault.microservices.AbstractTransport;
import com.jvfault.microservices.Message;
import com.jvfault.microservices.MessageCodec;
import com.jvfault.microservices.JacksonMessageCodec;
import com.jvfault.microservices.TransportClient;
import com.jvfault.microservices.TransportException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * TCP 传输服务端（BIO 线程池模型：4 字节大端长度 + JSON 载荷）。
 *
 * <p>消息 wire 格式：JSON 对象 {"pattern":..., "data":base64, "correlationId":..., "id":...}。
 *
 * @since v0.6.0 (2020)
 * @author jvfault team
 */
public class TcpTransportServer extends AbstractTransport {

    private static final Logger log = LoggerFactory.getLogger(TcpTransportServer.class);

    private final int port;
    private final MessageCodec codec = new JacksonMessageCodec();
    private ServerSocket serverSocket;
    private final ExecutorService acceptPool = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "jvfault-tcp-accept");
        t.setDaemon(true);
        return t;
    });
    private final AtomicBoolean closed = new AtomicBoolean(false);

    public TcpTransportServer(int port) {
        this.port = port;
    }

    public int getPort() {
        return port;
    }

    @Override
    public void bind() {
        try {
            serverSocket = new ServerSocket(port);
        } catch (Exception e) {
            throw new TransportException("TCP 绑定失败: " + port, e);
        }
        running = true;
        acceptPool.execute(() -> {
            while (!closed.get()) {
                try {
                    Socket socket = serverSocket.accept();
                    acceptPool.execute(() -> handleConnection(socket));
                } catch (Exception e) {
                    if (!closed.get()) {
                        log.warn("accept 失败", e);
                    }
                }
            }
        });
        log.info("TCP transport listening on {}", port);
    }

    private void handleConnection(Socket socket) {
        try (Socket s = socket) {
            DataInputStream in = new DataInputStream(s.getInputStream());
            DataOutputStream out = new DataOutputStream(s.getOutputStream());
            while (!closed.get() && !s.isClosed()) {
                int len;
                try {
                    len = in.readInt();
                } catch (EOFException eof) {
                    return; // 客户端正常断开
                }
                if (len > TcpWireMessage.MAX_FRAME) {
                    throw new TransportException("帧超限: " + len);
                }
                byte[] payload = new byte[len];
                in.readFully(payload);
                TcpWireMessage wire = codec.decode(payload, TcpWireMessage.class);
                Message response = dispatch(wire.toMessage());
                if (response != null) {
                    byte[] respBytes = codec.encode(TcpWireMessage.from(response));
                    out.writeInt(respBytes.length);
                    out.write(respBytes);
                    out.flush();
                }
            }
        } catch (Exception e) {
            if (!closed.get()) {
                log.debug("连接处理结束: {}", e.getMessage());
            }
        }
    }

    @Override
    public void publish(Message message) {
        // TCP 无内置广播语义，由处理器消费连接内消息
        dispatch(message);
    }

    @Override
    public Message request(Message request, long timeoutMillis) {
        throw new TransportException("TCP server 不支持本地 request，请使用 TcpTransportClient");
    }

    @Override
    public void close() {
        closed.set(true);
        running = false;
        try {
            if (serverSocket != null) {
                serverSocket.close();
            }
        } catch (Exception ignored) {
            // 关闭期 best-effort：监听 socket 可能已被 OS 回收或从未 bind 成功，
            // 这里的失败不能影响后续 acceptPool.shutdownNow() 的执行。
        }
        acceptPool.shutdownNow();
    }
}

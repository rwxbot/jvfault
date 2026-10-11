package com.jvfault.transport.redis;

import com.jvfault.microservices.JacksonMessageCodec;
import com.jvfault.microservices.Message;
import com.jvfault.microservices.MessageCodec;
import com.jvfault.microservices.TransportClient;
import com.jvfault.microservices.TransportException;
import com.jvfault.microservices.TransportTimeoutException;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.pubsub.RedisPubSubAdapter;
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection;
import io.lettuce.core.api.StatefulRedisConnection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Redis Pub/Sub 传输客户端（Lettuce 真实实现）。
 *
 * <p>request-reply：请求发布到 pattern，携带独占 replyChannel；
 * 客户端在 replyChannel 上等待 correlationId 匹配的响应。
 *
 * @since v0.6.0 (2020)
 * @author jvfault team
 */
public class RedisTransportClient implements TransportClient {

    private static final Logger log = LoggerFactory.getLogger(RedisTransportClient.class);

    private final ConnectionConfig config;
    private final MessageCodec codec = new JacksonMessageCodec();
    private RedisClient client;
    private StatefulRedisPubSubConnection<String, String> pubSub;
    private StatefulRedisConnection<String, String> publisher;
    private volatile boolean connected;
    private String replyChannel;
    private final ConcurrentHashMap<String, BlockingQueue<Message>> pending = new ConcurrentHashMap<>();

    public RedisTransportClient(ConnectionConfig config) {
        this.config = config;
    }

    @Override
    public synchronized void connect() {
        if (connected) {
            return;
        }
        config.validate();
        client = RedisClient.create(RedisURI.create(config.getBootstrap()));
        client.setDefaultTimeout(Duration.ofSeconds(10));
        pubSub = client.connectPubSub();
        replyChannel = "jvfault.reply." + UUID.randomUUID();
        pubSub.addListener(new RedisPubSubAdapter<String, String>() {
            @Override
            public void message(String channel, String body) {
                try {
                    RedisWire wire = codec.decode(body.getBytes(java.nio.charset.StandardCharsets.UTF_8), RedisWire.class);
                    Message message = wire.toMessage();
                    BlockingQueue<Message> queue = pending.get(message.getCorrelationId());
                    if (queue != null) {
                        queue.add(message);
                    }
                } catch (Exception e) {
                    // 原来是完全空的 catch：回包解析失败或被丢弃时，请求方只会在
                    // request() 里阻塞到超时，日志上却什么都看不到，排查时极易被
                    // 误判成「服务端没回」。保留「跳过坏包继续监听」的行为，但留下痕迹。
                    log.warn("Redis 回包解析失败，已丢弃该消息: {}", e.toString());
                }
            }
        });
        pubSub.sync().subscribe(replyChannel);
        publisher = client.connect();
        connected = true;
    }

    @Override
    public synchronized Message request(String pattern, Object payload, long timeoutMillis) {
        ensureConnected();
        String correlationId = UUID.randomUUID().toString();
        BlockingQueue<Message> queue = pending.computeIfAbsent(correlationId,
                k -> new LinkedBlockingQueue<>());
        try {
            Message request = new Message(pattern, codec.encode(payload), correlationId, null);
            RedisWire wire = RedisWire.from(request, replyChannel);
            publisher.sync().publish(pattern, asText(codec.encode(wire)));

            Message response = queue.poll(timeoutMillis, TimeUnit.MILLISECONDS);
            if (response == null) {
                throw new TransportTimeoutException("redis request 超时: " + pattern);
            }
            if (response.getHeader(Message.HEADER_ERROR) != null) {
                throw new TransportException("远端错误: " + response.getHeader(Message.HEADER_ERROR));
            }
            return response;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new TransportException("redis request 被中断", e);
        } finally {
            pending.remove(correlationId);
        }
    }

    @Override
    public synchronized void emit(String pattern, Object payload) {
        ensureConnected();
        publisher.sync().publish(pattern,
                asText(codec.encode(RedisWire.from(new Message(pattern, codec.encode(payload)), null))));
    }

    @Override
    public synchronized void close() {
        connected = false;
        if (pubSub != null) {
            pubSub.close();
        }
        if (publisher != null) {
            publisher.close();
        }
        if (client != null) {
            client.shutdown();
        }
    }

    private static String asText(byte[] bytes) {
        return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
    }

    private void ensureConnected() {
        if (!connected) {
            connect();
        }
    }

    public boolean isConnected() {
        return connected;
    }
}

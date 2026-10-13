package com.jvfault.transport.redis;

import com.jvfault.microservices.AbstractTransport;
import com.jvfault.microservices.JacksonMessageCodec;
import com.jvfault.microservices.Message;
import com.jvfault.microservices.MessageCodec;
import com.jvfault.microservices.TransportException;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.pubsub.RedisPubSubAdapter;
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection;
import io.lettuce.core.api.StatefulRedisConnection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Redis Pub/Sub 传输服务端（Lettuce 真实实现）。
 *
 * <p>语义：subscribe 的 pattern 即 redis psubscribe 模式（* / ? / [] 通配）；
 * 收到消息后分发给处理器；响应发布到请求的 replyChannel。
 *
 * @since v0.6.0 (2020)
 * @author jvfault team
 */
public class RedisTransportServer extends AbstractTransport {

    private static final Logger log = LoggerFactory.getLogger(RedisTransportServer.class);

    private final ConnectionConfig config;
    private final MessageCodec codec = new JacksonMessageCodec();
    private RedisClient client;
    private StatefulRedisPubSubConnection<String, String> pubSub;
    private StatefulRedisConnection<String, String> publisher;

    public RedisTransportServer(ConnectionConfig config) {
        this.config = config;
    }

    public ConnectionConfig getConfig() {
        return config;
    }

    @Override
    public void bind() {
        if (running) {
            return;
        }
        config.validate();
        RedisURI uri = RedisURI.create(config.getBootstrap());
        uri.setTimeout(Duration.ofSeconds(10));
        client = RedisClient.create(uri);
        pubSub = client.connectPubSub();
        pubSub.addListener(new RedisPubSubAdapter<String, String>() {
            @Override
            public void message(String pattern, String channel, String body) {
                try {
                    RedisWire wire = codec.decode(body.getBytes(java.nio.charset.StandardCharsets.UTF_8), RedisWire.class);
                    Message response;
                    try {
                        response = dispatch(wire.toMessage());
                    } catch (Exception e) {
                        // 处理器异常 -> error 头回传
                        if (wire.getReplyChannel() != null) {
                            Message error = new Message(wire.getPattern(), new byte[0], wire.getCorrelationId(),
                                    java.util.Collections.singletonMap(Message.HEADER_ERROR, String.valueOf(e.getMessage())));
                            publisher.sync().publish(wire.getReplyChannel(),
                                    asText(codec.encode(RedisWire.from(error, null))));
                        }
                        return;
                    }
                    if (response != null && wire.getReplyChannel() != null) {
                        RedisWire replyWire = RedisWire.from(response, null);
                        publisher.sync().publish(wire.getReplyChannel(), asText(codec.encode(replyWire)));
                    }
                } catch (Exception e) {
                    log.warn("redis 消息处理失败: {}", e.getMessage());
                }
            }
        });
        publisher = client.connect();
        running = true;
        // 注册已存在的订阅（subscribe 先于 bind 的场景）
        handlers.keySet().forEach(p -> pubSub.sync().psubscribe(p));
        log.info("redis transport bound: {}", config.getBootstrap());
    }

    @Override
    public void subscribe(String pattern, com.jvfault.microservices.MessageHandler handler) {
        super.subscribe(pattern, handler);
        if (running) {
            pubSub.sync().psubscribe(pattern);
        }
    }

    @Override
    public Message request(Message request, long timeoutMillis) {
        throw new TransportException("redis 服务端不支持本地 request（pub/sub 无请求语义）");
    }

    @Override
    public void publish(Message message) {
        if (!running) {
            bind();
        }
        publisher.sync().publish(message.getPattern(),
                asText(codec.encode(RedisWire.from(message, null))));
    }

    private static String asText(byte[] bytes) {
        return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
    }



    @Override
    public void close() {
        running = false;
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
}

package com.jvfault.transport.rmq;

import com.jvfault.microservices.JacksonMessageCodec;
import com.jvfault.microservices.Message;
import com.jvfault.microservices.MessageCodec;
import com.jvfault.microservices.TransportClient;
import com.jvfault.microservices.TransportException;
import com.jvfault.microservices.TransportTimeoutException;
import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import com.rabbitmq.client.DefaultConsumer;
import com.rabbitmq.client.Envelope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * RabbitMQ 传输客户端（amqp-client 真实实现）。
 *
 * <p>request-reply：请求发布到 topic exchange（routing-key = pattern），
 * 携带 {@code replyTo}（独占 reply 队列）与 {@code correlationId}；
 * 客户端在 reply 队列上等待 correlationId 匹配的响应。handler 异常经 error 头回传。
 *
 * @since v1.0.2 (2026)
 * @author jvfault team
 */
public class RabbitMQTransportClient implements TransportClient {

    private static final Logger log = LoggerFactory.getLogger(RabbitMQTransportClient.class);
    static final String EXCHANGE = "jvfault.topic";

    private final ConnectionConfig config;
    private final MessageCodec codec = new JacksonMessageCodec();
    private Connection connection;
    private Channel publishChannel;
    private Channel consumeChannel;
    private volatile boolean connected;
    private String replyQueue;
    private final ConcurrentHashMap<String, BlockingQueue<Message>> pending = new ConcurrentHashMap<>();

    /**
     * 发布专用锁对象（阿里评审 P2：synchronized 不能锁在非 final 的可变字段上）。
     *
     * <p>原实现是 {@code synchronized (publishChannel)}：{@code publishChannel} 是
     * 非 final 字段，{@link #connect()} 重连时会把它换成新 Channel。一旦发生替换，
     * 两个线程就会锁在<b>不同对象</b>上，互斥彻底失效；而 RabbitMQ Channel 本身
     * 又不是线程安全的，并发 basicPublish 会直接抛
     * {@code IllegalStateException} 或损坏帧。改用专用 final 锁对象后，
     * 锁身份恒定，与 Channel 是否重建无关。
     */
    private final Object publishLock = new Object();

    public RabbitMQTransportClient(ConnectionConfig config) {
        this.config = config;
    }

    @Override
    public synchronized void connect() {
        if (connected) {
            return;
        }
        config.validate();
        try {
            ConnectionFactory factory = new ConnectionFactory();
            try {
                factory.setUri(config.getBootstrap());
            } catch (Exception e) {
                throw new TransportException("RabbitMQ URI 非法: " + config.getBootstrap(), e);
            }
            factory.setConnectionTimeout(10_000);
            factory.setHandshakeTimeout(10_000);
            factory.setAutomaticRecoveryEnabled(true);
            connection = factory.newConnection();
            publishChannel = connection.createChannel();
            consumeChannel = connection.createChannel();
            publishChannel.exchangeDeclare(EXCHANGE, "topic", false, true, null);
            replyQueue = "jvfault.reply." + UUID.randomUUID();
            consumeChannel.queueDeclare(replyQueue, false, true, true, null);
            consumeChannel.basicConsume(replyQueue, true, new DefaultConsumer(consumeChannel) {
                @Override
                public void handleDelivery(String consumerTag, Envelope envelope,
                                           AMQP.BasicProperties properties, byte[] body) {
                    try {
                        RabbitMQWire wire = codec.decode(body, RabbitMQWire.class);
                        String corrId = properties.getCorrelationId() != null
                                ? properties.getCorrelationId() : wire.getCorrelationId();
                        BlockingQueue<Message> queue = pending.get(corrId);
                        if (queue != null) {
                            queue.add(wire.toMessage());
                        }
                    } catch (Exception ignored) {
                        // 无法解析的回包忽略
                    }
                }
            });
            connected = true;
        } catch (IOException | TimeoutException e) {
            throw new TransportException("RabbitMQ connect 失败: " + config.getBootstrap(), e);
        }
    }

    /**
     * 惰性建连。必须同步：原来的 {@code if (!connected) connect();} 是典型的
     * check-then-act 竞态 —— 两个线程同时看到 connected=false 就会各建一次连接，
     * 产生两套 reply 队列与消费者，后建的会把先建的 pending 映射彻底架空（响应
     * 永远等不到，最终表现为请求超时）。{@link #connect()} 本身已是
     * {@code synchronized}（可重入），这里加锁只补上「判断」这一步。
     */
    private synchronized void ensureConnected() {
        if (!connected) {
            connect();
        }
    }

    @Override
    public Message request(String pattern, Object payload, long timeoutMillis) {
        ensureConnected();
        String correlationId = UUID.randomUUID().toString();
        BlockingQueue<Message> queue = pending.computeIfAbsent(correlationId,
                k -> new LinkedBlockingQueue<>());
        try {
            Message request = new Message(pattern, codec.encode(payload), correlationId, null);
            AMQP.BasicProperties props = new AMQP.BasicProperties.Builder()
                    .correlationId(correlationId).replyTo(replyQueue).build();
            try {
                synchronized (publishLock) {
                    publishChannel.basicPublish(EXCHANGE, pattern, props,
                            codec.encode(RabbitMQWire.from(request)));
                }
            } catch (IOException e) {
                throw new TransportException("rabbitmq request 发送失败: " + pattern, e);
            }
            Message response = queue.poll(timeoutMillis, TimeUnit.MILLISECONDS);
            if (response == null) {
                throw new TransportTimeoutException("rabbitmq request 超时: " + pattern);
            }
            if (response.getHeader(Message.HEADER_ERROR) != null) {
                throw new TransportException("远端错误: " + response.getHeader(Message.HEADER_ERROR));
            }
            return response;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new TransportException("rabbitmq request 被中断", e);
        } finally {
            pending.remove(correlationId);
        }
    }

    @Override
    public void emit(String pattern, Object payload) {
        ensureConnected();
        Message message = new Message(pattern, codec.encode(payload));
        try {
            synchronized (publishLock) {
                publishChannel.basicPublish(EXCHANGE, pattern, null,
                        codec.encode(RabbitMQWire.from(message)));
            }
        } catch (IOException e) {
            throw new TransportException("rabbitmq emit 失败: " + pattern, e);
        }
    }

    @Override
    public synchronized void close() {
        connected = false;
        if (consumeChannel != null) {
            try {
                consumeChannel.close();
            } catch (Exception ignore) {
                // best-effort
            }
        }
        if (publishChannel != null) {
            try {
                publishChannel.close();
            } catch (Exception ignore) {
                // best-effort
            }
        }
        if (connection != null) {
            try {
                connection.close();
            } catch (Exception ignore) {
                // best-effort
            }
        }
    }

    public boolean isConnected() {
        return connected;
    }
}

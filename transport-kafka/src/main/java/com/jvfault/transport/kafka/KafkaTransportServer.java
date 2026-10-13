package com.jvfault.transport.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jvfault.microservices.AbstractTransport;
import com.jvfault.microservices.JacksonMessageCodec;
import com.jvfault.microservices.Message;
import com.jvfault.microservices.MessageCodec;
import com.jvfault.microservices.TransportException;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Kafka 传输服务端（kafka-clients 真实实现）。
 * topic 即 pattern；request-reply：处理器响应发布到请求的 replyTopic（按
 * correlationId 路由）；handler 异常经 error 头回传。
 *
 * @since v0.6.0 (2020)
 * @author jvfault team
 */
public class KafkaTransportServer extends AbstractTransport {

    private static final Logger log = LoggerFactory.getLogger(KafkaTransportServer.class);

    private final ConnectionConfig config;
    private final ObjectMapper mapper = new ObjectMapper();
    private final MessageCodec codec = new JacksonMessageCodec();

    private KafkaProducer<String, String> producer;
    private KafkaConsumer<String, String> consumer;
    private ExecutorService pollLoop;
    private final AtomicBoolean closed = new AtomicBoolean();
    private volatile boolean consumerClosed;

    public KafkaTransportServer(ConnectionConfig config) {
        this.config = config;
    }

    @Override
    public void bind() {
        if (running) {
            return;
        }
        config.validate();
        Map<String, Object> producerProps = new HashMap<>();
        producerProps.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, config.getBootstrap());
        producerProps.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        producerProps.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        producer = new KafkaProducer<>(producerProps);

        Map<String, Object> consumerProps = new HashMap<>();
        consumerProps.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, config.getBootstrap());
        consumerProps.put(ConsumerConfig.GROUP_ID_CONFIG, "jvfault-server-" + System.currentTimeMillis());
        consumerProps.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        consumerProps.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        consumerProps.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        consumerProps.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "true");
        consumerProps.put(ConsumerConfig.AUTO_COMMIT_INTERVAL_MS_CONFIG, "200");
        consumer = new KafkaConsumer<>(consumerProps, new StringDeserializer(), new StringDeserializer());

        if (!handlers.isEmpty()) {
            consumer.subscribe(handlers.keySet());
        }
        running = true;
        // 触发分区分配与首次 poll（确保客户端 publish 时 partition 已就绪）；
        // 未订阅任何 topic 时 poll 会抛 IllegalStateException，故仅在有订阅时调用。
        if (!handlers.isEmpty()) {
            consumer.poll(Duration.ofMillis(500));
        }

        pollLoop = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "jvfault-kafka-consumer");
            t.setDaemon(true);
            return t;
        });
        pollLoop.execute(this::pollLoop);
        log.info("Kafka transport bound: {}", config.getBootstrap());
    }

    @Override
    public void subscribe(String pattern, com.jvfault.microservices.MessageHandler handler) {
        boolean firstBind = handlers.isEmpty();
        super.subscribe(pattern, handler);
        if (running && firstBind) {
            consumer.subscribe(Collections.singletonList(pattern));
        } else if (running) {
            consumer.subscribe(handlers.keySet());
        }
    }

    private void pollLoop() {
        try {
            while (!closed.get()) {
                ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(200));
                for (ConsumerRecord<String, String> record : records) {
                    handleRecord(record);
                }
            }
        } catch (Exception e) {
            if (!closed.get()) {
                log.warn("Kafka consumer loop 异常: {}", e.getMessage());
            }
        } finally {
            // KafkaConsumer 非线程安全：必须由轮询线程自己关闭，
            // 否则调用方线程 close() 会抛 ConcurrentModificationException。
            try {
                consumer.close();
            } catch (Exception ignored) {
                // ignore
            } finally {
                consumerClosed = true;
            }
        }
    }

    private void handleRecord(ConsumerRecord<String, String> record) {
        try {
            Message message = KafkaWire.deserialize(record.value(), mapper);
            Message response;
            try {
                response = dispatch(message);
            } catch (Exception e) {
                String replyTopic = extractReplyTopic(record.value());
                if (replyTopic != null) {
                    Message error = new Message(message.getPattern(), new byte[0], message.getCorrelationId(),
                            Collections.singletonMap(Message.HEADER_ERROR, String.valueOf(e.getMessage())));
                    producer.send(new ProducerRecord<>(replyTopic, record.key(),
                            KafkaWire.serialize(error, mapper, null)));
                }
                return;
            }
            if (response == null) {
                return;
            }
            String replyTopic = extractReplyTopic(record.value());
            if (replyTopic != null) {
                producer.send(new ProducerRecord<>(replyTopic, record.key(),
                        KafkaWire.serialize(response, mapper, null)));
            }
        } catch (Exception e) {
            log.warn("Kafka record 处理失败: {}", e.getMessage());
        }
    }

    private String extractReplyTopic(String body) {
        try {
            return mapper.readTree(body).path("replyTopic").textValue();
        } catch (Exception e) {
            // replyTopic 是可选字段：报文不是 JSON、或压根没带 replyTopic 都属正常
            // （纯事件消息就没有回复地址），返回 null 表示「无需回复」。
            return null;
        }
    }

    @Override
    public Message request(Message request, long timeoutMillis) {
        throw new TransportException("Kafka 服务端不支持本地 request（事件语义）");
    }

    @Override
    public void publish(Message message) {
        ensureRunning();
        producer.send(new ProducerRecord<>(message.getPattern(), message.getId(),
                KafkaWire.serialize(message, mapper, null)));
    }

    @Override
    public void close() {
        running = false;
        closed.set(true);
        if (pollLoop != null) {
            // wakeup 必须从别的线程调用才能让阻塞中的 poll 立即返回
            if (consumer != null) {
                consumer.wakeup();
            }
            pollLoop.shutdownNow();
            try {
                if (!pollLoop.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS)) {
                    log.warn("Kafka poll loop 未在 5s 内退出");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if (consumer != null && !consumerClosed) {
            consumer.wakeup();
            consumer.close();
        }
        if (producer != null) {
            producer.flush();
            producer.close();
        }
    }

    private void ensureRunning() {
        if (!running) {
            bind();
        }
    }
}

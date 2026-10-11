package com.jvfault.transport.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jvfault.microservices.JacksonMessageCodec;
import com.jvfault.microservices.Message;
import com.jvfault.microservices.MessageCodec;
import com.jvfault.microservices.TransportClient;
import com.jvfault.microservices.TransportException;
import com.jvfault.microservices.TransportTimeoutException;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;

import java.time.Duration;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Kafka 传输客户端（kafka-clients 真实实现）。
 * request-reply：发布请求到 pattern topic，携带 replyTopic；客户端订阅
 * replyTopic，按 key=correlationId 匹配响应；handler 异常经 error 头回传。
 *
 * @since v0.6.0 (2020)
 * @author jvfault team
 */
public class KafkaTransportClient implements TransportClient {

    private final ConnectionConfig config;
    private final MessageCodec codec = new JacksonMessageCodec();
    private final ObjectMapper mapper = new ObjectMapper();

    private KafkaProducer<String, String> producer;
    private KafkaConsumer<String, String> replyConsumer;
    private volatile boolean connected;
    private String replyTopic;

    public KafkaTransportClient(ConnectionConfig config) {
        this.config = config;
    }

    @Override
    public void connect() {
        if (connected) {
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
        consumerProps.put(ConsumerConfig.GROUP_ID_CONFIG, "jvfault-client-" + UUID.randomUUID());
        consumerProps.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "latest");
        consumerProps.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        consumerProps.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        replyConsumer = new KafkaConsumer<>(consumerProps, new StringDeserializer(), new StringDeserializer());
        replyTopic = "jvfault.reply." + UUID.randomUUID();
        replyConsumer.subscribe(Collections.singletonList(replyTopic));
        // 触发分区分配（latest offset 从当前位置读，无需太久）
        replyConsumer.poll(Duration.ofMillis(500));
        connected = true;
    }

    @Override
    public Message request(String pattern, Object payload, long timeoutMillis) {
        ensureConnected();
        String correlationId = UUID.randomUUID().toString();
        Message request = new Message(pattern, codec.encode(payload), correlationId, null);
        producer.send(new ProducerRecord<>(pattern, correlationId,
                KafkaWire.serialize(request, mapper, replyTopic)));

        long deadline = System.nanoTime() + timeoutMillis * 1_000_000L;
        while (System.nanoTime() < deadline) {
            // poll(long) 自 Kafka 2.0 起标记废弃（4.0 计划移除），改用 Duration 重载。
            ConsumerRecords<String, String> records = replyConsumer.poll(java.time.Duration.ofMillis(
                    Math.max(50L, Math.min(500L, (deadline - System.nanoTime()) / 1_000_000))));
            for (ConsumerRecord<String, String> record : records) {
                if (!correlationId.equals(record.key())) {
                    continue;
                }
                Message response = KafkaWire.deserialize(record.value(), mapper);
                if (response.getHeaders().get(Message.HEADER_ERROR) != null) {
                    throw new TransportException("远端错误: "
                            + response.getHeaders().get(Message.HEADER_ERROR));
                }
                return response;
            }
        }
        throw new TransportTimeoutException("Kafka request 超时: " + pattern);
    }

    @Override
    public void emit(String pattern, Object payload) {
        ensureConnected();
        Message message = new Message(pattern, codec.encode(payload));
        producer.send(new ProducerRecord<>(pattern, message.getId(),
                KafkaWire.serialize(message, mapper, null)));
    }

    @Override
    public void close() {
        connected = false;
        if (replyConsumer != null) {
            replyConsumer.close();
        }
        if (producer != null) {
            producer.flush();
            producer.close();
        }
    }

    public boolean isConnected() {
        return connected;
    }

    private void ensureConnected() {
        if (!connected) {
            connect();
        }
    }
}

package com.jvfault.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Iterator;
import java.util.List;

/**
 * OpenAI 兼容 ChatModel（覆盖 OpenAI/兼容网关/vLLM 等）。
 * 对应 Spring AI: OpenAiChatModel
 *
 * <p>流式实现：使用 SSE 事件流逐块回调。
 *
 * @since v0.11.0 (2025)
 * @author jvfault team
 */
public class OpenAiCompatibleChatModel implements ChatModel {

    private static final Logger log = LoggerFactory.getLogger(OpenAiCompatibleChatModel.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String baseUrl;
    private final String apiKey;
    private final String model;
    private final HttpClient http;

    public OpenAiCompatibleChatModel(String baseUrl, String apiKey, String model) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.apiKey = apiKey;
        this.model = model;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .build();
    }

    @Override
    public String getModelId() {
        return "openai:" + model;
    }

    @Override
    public ChatResponse call(ChatRequest request) {
        try {
            HttpRequest outgoing = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/chat/completions"))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(buildBody(request, false)))
                    .build();
            HttpResponse<String> response = http.send(outgoing, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400) {
                throw new IllegalStateException("LLM 调用失败 HTTP " + response.statusCode()
                        + ": " + response.body());
            }
            JsonNode root = MAPPER.readTree(response.body());
            String content = root.path("choices").path(0).path("message").path("content").textValue();
            JsonNode usage = root.path("usage");
            return new ChatResponse(content, root.path("model").textValue(),
                    new ChatResponse.Usage(
                            usage.path("prompt_tokens").asLong(),
                            usage.path("completion_tokens").asLong(),
                            usage.path("total_tokens").asLong()));
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("LLM 调用异常: " + e.getMessage(), e);
        }
    }

    @Override
    public void stream(ChatRequest request, java.util.function.Consumer<String> chunkConsumer) {
        try {
            HttpRequest httpRequest = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/chat/completions"))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(buildBody(request, true)))
                    .build();
            HttpResponse<java.util.stream.Stream<String>> response =
                    http.send(httpRequest, HttpResponse.BodyHandlers.ofLines());
            response.body().forEach(line -> {
                if (line.startsWith("data: ") && !line.equals("data: [DONE]")) {
                    try {
                        JsonNode delta = MAPPER.readTree(line.substring(6))
                                .path("choices").path(0).path("delta");
                        String text = delta.path("content").textValue();
                        if (text != null && !text.isEmpty()) {
                            chunkConsumer.accept(text);
                        }
                    } catch (Exception e) {
                        // 原来是完全空的 catch：SSE 流里任何一块解析失败都会被静默丢弃，
                        // 调用方只会看到「响应提前结束」，无法区分是模型侧结束还是解析出错。
                        // 这里保留「跳过坏块继续流」的行为（流不能因为一块坏数据就中断），
                        // 但必须留下可观测痕迹。
                        log.warn("SSE 数据块解析失败，已跳过该块: {}",
                                e.toString());
                    }
                }
            });
        } catch (Exception e) {
            throw new IllegalStateException("LLM 流式调用异常: " + e.getMessage(), e);
        }
    }

    private String buildBody(ChatRequest request, boolean stream) throws Exception {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("model", model);
        body.put("stream", stream);
        ArrayNode messages = body.putArray("messages");
        for (ChatRequest.Message message : request.getMessages()) {
            ObjectNode m = messages.addObject();
            m.put("role", message.role());
            m.put("content", message.content());
        }
        Object opts = request.getOptions().get("temperature");
        if (opts instanceof Number n) {
            body.put("temperature", n.doubleValue());
        }
        if (!request.getTools().isEmpty()) {
            ArrayNode tools = body.putArray("tools");
            for (ChatRequest.Tool tool : request.getTools()) {
                ObjectNode t = tools.addObject();
                t.put("type", "function");
                ObjectNode fn = t.putObject("function");
                fn.put("name", tool.name());
                fn.put("description", tool.description());
                fn.putObject("parameters").set("raw", MAPPER.readTree(tool.jsonSchema()));
            }
        }
        return MAPPER.writeValueAsString(body);
    }
}

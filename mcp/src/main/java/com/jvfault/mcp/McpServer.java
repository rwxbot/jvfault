package com.jvfault.mcp;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * MCP (Model Context Protocol) 服务器核心 —— 工具/资源注册与
 * JSON-RPC 2.0 消息处理。
 * 对应 roadmap v0.11.0: MCP Server
 *
 * <p>支持的方法：
 * <ul>
 *   <li>initialize → 能力握手</li>
 *   <li>tools/list → 工具清单</li>
 *   <li>tools/call → 调用工具</li>
 *   <li>resources/list → 资源清单</li>
 * </ul>
 *
 * @since v0.11.0 (2025)
 * @author jvfault team
 */
public class McpServer {

    private final String serverName;
    private final String serverVersion;
    private final Map<String, ToolHandler> tools = new LinkedHashMap<>();
    private final Map<String, String> resources = new LinkedHashMap<>();

    public McpServer(String serverName, String serverVersion) {
        this.serverName = serverName;
        this.serverVersion = serverVersion;
    }

    /** 工具处理器 */
    @FunctionalInterface
    public interface ToolHandler {
        String call(Map<String, Object> arguments) throws Exception;
    }

    public McpServer tool(String name, String description, ToolHandler handler) {
        tools.put(name, handler);
        return this;
    }

    public McpServer resource(String uri, String content) {
        resources.put(uri, content);
        return this;
    }

    /**
     * 处理 JSON-RPC 请求，返回 JSON-RPC 响应（通知类返回 null）。
     */
    public String handle(String jsonRpcRequest) {
        McpCodec.Request request = McpCodec.parseRequest(jsonRpcRequest);
        if (request.id() == null) {
            return null; // notification
        }
        try {
            Object result = dispatch(request);
            return McpCodec.success(request.id(), result);
        } catch (Exception e) {
            return McpCodec.error(request.id(), -32603, String.valueOf(e.getMessage()));
        }
    }

    private Object dispatch(McpCodec.Request request) throws Exception {
        switch (request.method()) {
            case "initialize" -> {
                return McpCodec.jsonObject()
                        .put("protocolVersion", "2024-11-05")
                        .put("serverInfo", McpCodec.jsonObject()
                                .put("name", serverName)
                                .put("version", serverVersion))
                        .build();
            }
            case "tools/list" -> {
                McpCodec.JsonArrayBuilder toolsArr = McpCodec.jsonArray();
                for (Map.Entry<String, ToolHandler> tool : tools.entrySet()) {
                    toolsArr.add(McpCodec.jsonObject()
                            .put("name", tool.getKey())
                            .put("description", "tool " + tool.getKey())
                            .build());
                }
                return McpCodec.jsonObject().put("tools", toolsArr.build()).build();
            }
            case "tools/call" -> {
                String toolName = request.params().path("name").textValue();
                ToolHandler handler = tools.get(toolName);
                if (handler == null) {
                    throw new IllegalArgumentException("未知工具: " + toolName);
                }
                Map<String, Object> args = McpCodec.toMap(request.params().path("arguments"));
                String output = handler.call(args != null ? args : Collections.emptyMap());
                return McpCodec.jsonObject()
                        .put("content", McpCodec.jsonArray()
                                .add(McpCodec.jsonObject().put("type", "text").put("text", output).build())
                                .build())
                        .build();
            }
            case "resources/list" -> {
                McpCodec.JsonArrayBuilder resourcesArr = McpCodec.jsonArray();
                for (String uri : resources.keySet()) {
                    resourcesArr.add(McpCodec.jsonObject().put("uri", uri).build());
                }
                return McpCodec.jsonObject().put("resources", resourcesArr.build()).build();
            }
            case "resources/read" -> {
                String uri = request.params().path("uri").textValue();
                if (!resources.containsKey(uri)) {
                    throw new IllegalArgumentException("资源不存在: " + uri);
                }
                return McpCodec.jsonObject()
                        .put("contents", McpCodec.jsonArray()
                                .add(McpCodec.jsonObject()
                                        .put("uri", uri)
                                        .put("text", resources.get(uri))
                                        .build())
                                .build())
                        .build();
            }
            default -> throw new IllegalArgumentException("不支持的方法: " + request.method());
        }
    }
}

# jvfault AI 栈（ai / rag / mcp）

> 本文覆盖 `ai`、`rag`、`mcp` 三个模块（自 **v0.11.0 AI Native** 引入，v1.0.16 状态）。
> 三者**互相独立、可单独使用**：只用 ChatModel 不必引入 rag/mcp，反之亦然。
> 编译基线与框架默认一致（`--release 17`），**无特殊 JDK 要求**。

---

## 一、概览

| 模块 | 职责 | 关键类型 | 对标 Spring AI |
|------|------|---------|---------------|
| `ai` | 聊天模型统一调用（含流式、工具调用） | `ChatModel`、`ChatRequest`、`ChatResponse`、`OpenAiCompatibleChatModel` | `ChatModel` |
| `rag` | 检索增强生成（分块 → 向量检索 → 上下文注入） | `VectorStore`、`InMemoryVectorStore`、`TextChunker`、`RagPipeline` | `VectorStore` |
| `mcp` | Model Context Protocol 服务端（JSON-RPC） | `McpServer`、`McpCodec`（包级私有） | MCP Server |

**设计取向**：只做**薄 SPI + 一个可直接用的默认实现**，不绑定任何厂商 SDK。
`ai` 模块用 JDK 内置 `java.net.http.HttpClient`（零外部依赖）直连任意 OpenAI 兼容端点；
`rag` 的向量化由调用方注入 `Function<String, float[]>`（**框架不自带 EmbeddingModel**）。

---

## 二、`ai` —— ChatModel SPI

### 核心接口

`ChatModel` 是**函数式接口**（`@FunctionalInterface`），只需实现 `call()`：

```java
@FunctionalInterface
public interface ChatModel {
    default String getModelId() { return getClass().getSimpleName(); }
    ChatResponse call(ChatRequest request);
    // 默认实现：阻塞调用后一次性回调整个 content
    default void stream(ChatRequest request, Consumer<String> chunkConsumer) {
        chunkConsumer.accept(call(request).content());
    }
}
```

> ⚠️ `stream()` 的默认实现**不是真流式** —— 它调 `call()` 后一次性把全文交给回调。
> 只有 `OpenAiCompatibleChatModel` **覆写了**它，走 SSE 逐块回调。
> 自实现 `ChatModel` 时若不覆写 `stream()`，调用方拿到的是"假流"。

### 数据模型

| 类型 | 说明 |
|------|------|
| `ChatRequest` | 链式构造：`.system()` / `.user()` / `.assistant()` / `.option(k,v)` / `.tool(tool)` |
| `ChatRequest.Message` | `record(String role, String content)` |
| `ChatRequest.Tool` | `record(String name, String description, String jsonSchema)` —— schema 传 JSON 字符串 |
| `ChatResponse` | `record(String content, String model, Usage usage)` |
| `ChatResponse.Usage` | `record(long promptTokens, long completionTokens, long totalTokens)` |

### `OpenAiCompatibleChatModel`

```java
new OpenAiCompatibleChatModel(String baseUrl, String apiKey, String model)
```

- 覆盖 OpenAI 官方与所有兼容网关（vLLM、各类中转），请求打到 `${baseUrl}/chat/completions`
- 连接超时固定 15s（`HttpClient.newBuilder().connectTimeout(...)`，**不可配置**）
- **流式**：`stream()` 走 SSE，逐块解析 `choices[0].delta.content` 并回调
- **工具调用**：`ChatRequest.tool(...)` 的 schema 原样塞进 `tools[].function.parameters.raw`
- 非 2xx 响应抛 `IllegalStateException("LLM 调用失败 HTTP ...")`

**用法**：

```java
ChatModel model = new OpenAiCompatibleChatModel(
        "https://api.openai.com/v1", System.getenv("OPENAI_API_KEY"), "gpt-4o-mini");

// 单次调用
ChatResponse resp = model.call(new ChatRequest()
        .system("你是 jvfault 框架助手")
        .user("用一句话解释什么是 SPI")
        .option("temperature", 0.2));
System.out.println(resp.content());
System.out.println("tokens=" + resp.usage().totalTokens());

// 流式（真 SSE）
model.stream(new ChatRequest().user("讲个笑话"), chunk -> System.out.print(chunk));
```

**v1.0.16 变更**：SSE 流式里单块解析失败**不再静默丢弃** —— 原实现是完全空的 `catch`，
调用方只看到"响应提前结束"，分不清是模型侧结束还是解析出错。
现补 `log.warn`（保留"跳过坏块继续流"的语义，流不会因一块坏数据中断）。
同时把 Jackson 废弃的 `asText(String)` 换成 `textValue()`。

---

## 三、`rag` —— 检索增强

### `VectorStore` SPI

```java
public interface VectorStore {
    void add(String id, String document, float[] embedding, Map<String, Object> metadata);
    List<SearchHit> similaritySearch(float[] queryEmbedding, int topK); // 余弦相似度降序
    int size();
    record SearchHit(String id, String document, float score, Map<String, Object> metadata) {}
}
```

`InMemoryVectorStore` 是默认实现：`ConcurrentHashMap` 存储，余弦相似度检索，
`add()` 同 id 覆盖写入。**进程内、重启即失** —— 仅适合测试与小规模场景。

### `TextChunker`

```java
new TextChunker(int chunkSize, int overlap)   // overlap >= chunkSize 时抛 IllegalArgumentException
```

滑动窗口 + 重叠，**句子边界优先、字符长度兜底**。

> ⚠️ 句子边界仅识别 **中文句号 `。`** 与 **英文 `". "`（点+空格）**。
> `！`/`？`/`\n` 等其他标点**不会**被当作边界，遇到这类文本会退化为纯字符切分
> （可能从句中截断）。这是当前实现的已知局限。

### `RagPipeline`

```java
new RagPipeline(VectorStore vectorStore, Function<String, float[]> embeddingFunction, int topK)
```

| 方法 | 作用 |
|------|------|
| `retrieve(query)` | 向量化 query → `similaritySearch` → 命中列表 |
| `buildContext(query)` | 命中片段用 `\n---\n` 拼接成上下文 |
| `generate(query, generator)` | 端到端：拼上下文 → 套模板 → 调 `generator` |
| `describe()` | 调试用：`{topK, storeSize}` |

> ⚠️ **重排（Rerank）阶段尚未实现。** roadmap v0.11.0 写的是
> `Retrieve→Rerank→Generate`，但 `retrieve()` 直接返回 `similaritySearch` 的结果，
> **未做二次排序**（已在源码 Javadoc 明确标注）。
> 另注意 `buildContext()` 内部固定走 `retrieve()`，**不接受外部传入的命中列表** ——
> 需要重排时请自行对 `retrieve()` 结果排序后拼接。

**用法**：

```java
VectorStore store = new InMemoryVectorStore();
Function<String, float[]> embed = text -> myEmbedder.embed(text);  // 由调用方提供

TextChunker chunker = new TextChunker(500, 50);
int i = 0;
for (String chunk : chunker.chunk(document)) {
    store.add("doc-" + i++, chunk, embed.apply(chunk), Map.of("source", "manual"));
}

RagPipeline pipeline = new RagPipeline(store, embed, 3);
String answer = pipeline.generate("怎么配置重试？",
        prompt -> model.call(new ChatRequest().user(prompt)).content());
```

`generate()` 内置的 prompt 模板（**固定、不可改**）：

```
参考以下资料回答问题。

资料:
<拼接的上下文>

问题: <query>
```

模板不满足需求时，改用 `buildContext()` 自行拼装 prompt。

---

## 四、`mcp` —— Model Context Protocol

`McpServer` 是一个**JSON-RPC 方法分发器**：注册工具与资源，然后 `handle()` 一条请求文本、
返回一条响应文本（通知类请求返回 `null`）。

```java
McpServer server = new McpServer("demo", "1.0.0")
        .tool("echo", "回显输入", args -> String.valueOf(args.get("text")))
        .resource("file:///readme.md", "# jvfault");

String resp = server.handle("""
        {"jsonrpc":"2.0","id":1,"method":"tools/call",
         "params":{"name":"echo","arguments":{"text":"hi"}}}
        """);
```

- `ToolHandler` 是 `@FunctionalInterface`：`String call(Map<String, Object> arguments) throws Exception`
  —— **返回 JSON 字符串**，不是 `JsonNode`；抛异常会被转成 JSON-RPC 错误码 `-32603`
- `McpCodec` 是**包级私有**，外部不可直接调用

### 支持的方法

| 方法 | 说明 |
|------|------|
| `initialize` | 返回 `protocolVersion` / `serverInfo` / `capabilities` |
| `tools/list` | 列出已注册工具（name + description） |
| `tools/call` | 按 `params.name` 调用；工具不存在抛 `-32603` |
| `resources/list` | 列出已注册资源 URI |
| `resources/read` | 按 `params.uri` 读内容；不存在抛 `-32603` |

**v1.0.16 变更**：Jackson 废弃的 `asText()` 换成 `textValue()`（`McpCodec` / `McpServer`）。

---

## 五、已知边界与缺口

诚实清单，避免"以为有、实际没有"：

| 项 | 现状 |
|----|------|
| **EmbeddingModel** | **没有**。向量化由调用方注入 `Function<String, float[]>`，框架不提供嵌入模型 |
| **Rerank（重排）** | **未实现**。`RagPipeline` 只有 Retrieve → Generate |
| **MCP prompts** | **未实现**。仅 `tools` 与 `resources`，MCP 规范的 `prompts` 能力缺失 |
| **MCP 传输层** | 无独立 transport，`handle()` 只做字符串进出，需调用方接自己的 IO |
| **连接超时** | `OpenAiCompatibleChatModel` 固定 15s，**不可配置**（无读取超时设置） |
| **向量持久化** | `InMemoryVectorStore` 仅内存，重启即失；无 Redis/PG 等实现 |
| **流式默认实现** | `ChatModel.stream()` 默认是"假流"（阻塞后一次性回调），需自行覆写 |
| **分块边界** | `TextChunker` 只认 `。` 与 `". "`，其余标点退化为字符切分 |
| **重试 / 限流** | 无。生产使用需自行在外层包装 |

---

## 六、相关文档

- 模块维度表与 JDK 分层：`README.md`
- 版本变更：`CHANGELOG.md` → `v0.11.0 AI Native`（引入）、`v1.0.16`（SSE 可观测性修复）
- 缺陷台账：`docs/review/findings/2026-10-12-tag-defect-ledger.md`（ai SSE 空 catch）

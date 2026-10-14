package com.jvfault.rag;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * RAG 管道：检索 → 生成上下文注入。
 *
 * <p>对应 roadmap v0.11.0 的 Retrieve→Rerank→Generate，但<b>重排（Rerank）阶段尚未实现</b>：
 * {@link #retrieve(String)} 直接返回 {@link VectorStore#similaritySearch(float[], int)} 的结果，
 * 未做二次排序。注意 {@link #buildContext(String)} 内部固定走 {@code retrieve()}，
 * 不接受外部传入的命中列表 —— 需要重排时请自行对 {@code retrieve()} 的结果排序后拼接上下文。
 *
 * @since v0.11.0 (2025)
 * @author jvfault team
 */
public class RagPipeline {

    private final VectorStore vectorStore;
    private final Function<String, float[]> embeddingFunction;
    private final int topK;

    public RagPipeline(VectorStore vectorStore, Function<String, float[]> embeddingFunction, int topK) {
        this.vectorStore = vectorStore;
        this.embeddingFunction = embeddingFunction;
        this.topK = topK;
    }

    /**
     * 检索相关文档片段。
     */
    public List<VectorStore.SearchHit> retrieve(String query) {
        return vectorStore.similaritySearch(embeddingFunction.apply(query), topK);
    }

    /**
     * 组装增强上下文（拼接命中片段）。
     */
    public String buildContext(String query) {
        return retrieve(query).stream()
                .map(VectorStore.SearchHit::document)
                .collect(Collectors.joining("\n---\n"));
    }

    /**
     * 端到端：检索增强后调用生成函数。
     */
    public String generate(String query, Function<String, String> generator) {
        String context = buildContext(query);
        String prompt = "参考以下资料回答问题。\n\n资料:\n" + context + "\n\n问题: " + query;
        return generator.apply(prompt);
    }

    /** 供调试：当前 topK */
    public int getTopK() {
        return topK;
    }

    /** 供调试 */
    public Map<String, Object> describe() {
        return Map.of("topK", topK, "storeSize", vectorStore.size());
    }
}

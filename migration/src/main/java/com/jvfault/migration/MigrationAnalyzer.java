package com.jvfault.migration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * 迁移分析器 —— 扫描源码目录，按规则表输出迁移建议报告。
 *
 * @since v1.0.0 (2026)
 * @author jvfault team
 */
public class MigrationAnalyzer {

    private static final Logger log = LoggerFactory.getLogger(MigrationAnalyzer.class);

    private final List<RewriteRule> rules;

    public MigrationAnalyzer(List<RewriteRule> rules) {
        this.rules = rules;
    }

    public static MigrationAnalyzer withDefaults() {
        return new MigrationAnalyzer(RewriteRule.defaults());
    }

    /**
     * 扫描 .java 源文件，产出迁移发现。
     */
    public List<Finding> analyze(Path sourceDir) throws IOException {
        List<Finding> findings = new ArrayList<>();
        // 读不了的文件要留名：原来是裸 `return;` 静默跳过，报告会写成「发现 0 处」，
        // 让人误以为代码已经迁移干净，实际只是扫不动。这里收集起来，扫描结束后告警。
        List<String> skipped = new ArrayList<>();
        try (Stream<Path> files = Files.walk(sourceDir)) {
            files.filter(p -> p.toString().endsWith(".java")).forEach(file -> {
                String content;
                try {
                    content = Files.readString(file);
                } catch (IOException e) {
                    skipped.add(file.toString());
                    return;
                }
                for (RewriteRule rule : rules) {
                    String token = rule.from().contains("@")
                            ? rule.from().substring(rule.from().indexOf('@')) : rule.from();
                    if (content.contains(token)) {
                        findings.add(new Finding(file.toString(), rule, token));
                    }
                }
            });
        }
        if (!skipped.isEmpty()) {
            log.warn("迁移扫描跳过了 {} 个无法读取的文件（报告中的数量不包含这些文件）：{}",
                    skipped.size(), skipped.size() > 10 ? skipped.subList(0, 10) : skipped);
        }
        return findings;
    }

    /**
     * 生成 Markdown 迁移报告。
     */
    public String report(List<Finding> findings) {
        StringBuilder sb = new StringBuilder("# jvfault 迁移报告\n\n");
        sb.append("发现 ").append(findings.size()).append(" 处需要迁移：\n\n");
        for (Finding f : findings) {
            sb.append("- `").append(f.file()).append("` — ")
                    .append(f.rule().from()).append(" → ")
                    .append(f.rule().to());
            if (!f.rule().note().isEmpty()) {
                sb.append("（").append(f.rule().note()).append("）");
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    /** 迁移发现 */
    public record Finding(String file, RewriteRule rule, String matchedToken) {
    }
}

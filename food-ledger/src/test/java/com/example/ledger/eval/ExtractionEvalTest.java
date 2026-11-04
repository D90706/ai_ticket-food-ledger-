package com.example.ledger.eval;

import com.example.ledger.entry.ExtractionResult;
import com.example.ledger.entry.Extractor;
import com.example.ledger.entry.PromptBuilder;
import com.example.ledger.llm.LlmClient;
import com.example.ledger.record.ComplianceEvaluator;
import com.example.ledger.record.LedgerQuality;
import com.example.ledger.template.TemplateField;
import com.example.ledger.template.TemplateSchema;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 抽取的定量评测。
 *
 * 这个测试回答一个别的东西回答不了的问题：**它到底有多准。**
 * 单元测试能证明「判定规则对」，但证明不了「模型看懂了中国话」。
 *
 * 三个刻意的设计：
 *
 * 1 默认不跑。
 *    它会真实调用模型（16 条 ≈ 16 次调用，花钱且慢）。
 *    用 -Deval=true 显式开启，日常 mvn test 不受影响。
 *
 * 2 模板从 data.sql 里读，不另存一份。
 *    评测一旦用了第二份字段定义，改模板后指标就失真了，
 *    而且没人会记得去同步。
 *
 * 3 除了准确率，还算「瞎填率」和「编造率」。
 *    字段抽错是能力问题，把没说的编出来是性质问题——
 *    后者在台账里等于造假记录，必须单独盯。
 */
class ExtractionEvalTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 28);
    private static final Pattern KEY_PATTERN =
            Pattern.compile("api-key:\\s*\\$\\{DEEPSEEK_API_KEY:([^}]*)}");

    @Test
    @DisplayName("定量评测：字段准确率 / 瞎填率 / 编造率 / 级别一致率")
    void evaluate() throws Exception {
        // 用环境变量门控，不用 -D：Maven 的 -D 不会自动进入 fork 出来的测试 JVM，
        // 之前用 -Deval=true 时测试被静默跳过，很容易误以为"跑过了"。
        boolean enabled = "1".equals(System.getenv("RUN_EVAL"))
                || Boolean.getBoolean("eval");
        String apiKey = resolveApiKey();
        System.out.println("[eval] RUN_EVAL=" + System.getenv("RUN_EVAL")
                + " evalProp=" + System.getProperty("eval")
                + " apiKey=" + (apiKey == null ? "缺失" : "已配置(" + apiKey.length() + "位)"));
        assumeTrue(enabled, "定量评测会真实调用模型，请用 RUN_EVAL=1 显式开启");
        assumeTrue(apiKey != null && !apiKey.isBlank(), "没有可用的 DEEPSEEK_API_KEY，跳过评测");

        TemplateSchema schema = loadSchemaFromSeed();
        Extractor extractor = newExtractor(apiKey);

        List<Case> cases = loadCases();
        List<Outcome> outcomes = new ArrayList<>();

        for (Case c : cases) {
            String dialogue = String.join("\n", c.dialogue);
            ExtractionResult result = extractor.extract(schema, dialogue, TODAY);
            outcomes.add(compare(c, result, schema));
        }

        String report = report(outcomes, schema);
        Path target = Path.of("target", "eval-report.md");
        Files.createDirectories(target.getParent());
        Files.writeString(target, report, StandardCharsets.UTF_8);
        System.out.println(report);

        // 评测不做硬断言（模型有波动），只保证最基本的底线：
        // 字段准确率不该低于 0.6，否则这套 prompt 需要重写。
        double accuracy = overallAccuracy(outcomes);
        assertTrue(accuracy >= 0.6,
                "字段准确率过低（" + pct(accuracy) + "），请检查 prompt 或换模型");
    }

    // ---------------------------------------------------------- 比对

    private Outcome compare(Case c, ExtractionResult extracted, TemplateSchema schema) {
        Outcome o = new Outcome();
        o.id = c.id;
        o.note = c.note;
        o.basisGiven = extracted.getBasisGiven();
        o.basisDropped = extracted.getBasisDropped();
        o.degraded = extracted.isDegraded();

        for (Map.Entry<String, Object> e : c.expect.entrySet()) {
            String key = e.getKey();
            Object expected = e.getValue();
            Object actual = extracted.getValues().get(key);
            o.compared++;
            if (sameValue(expected, actual)) {
                o.matched++;
            } else {
                o.misses.add(key + " 期望「" + show(expected) + "」实得「" + show(actual) + "」");
            }

            if (expected == null) {
                o.expectEmpty++;
                if (!isBlank(actual)) {
                    o.falselyFilled++;
                }
            } else {
                o.expectFilled++;
                if (sameValue(expected, actual)) {
                    o.correctlyFilled++;
                }
            }
        }

        // 级别一致性：抽出来的值判一次，人工标注的值判一次。
        //
        // 关键：两边都必须只在「被标注的字段」上判定。
        // 一开始我拿全量 12 项必填去判人工标注那侧，结果没被标注的字段
        // 一律算缺失，人工侧永远是 RED——这个指标就成了测量标注覆盖度，
        // 而不是测量模型。第一版跑出 81.3% 的一致率就是这么来的。
        List<TemplateField> annotated = schema.fields().stream()
                .filter(f -> c.expect.containsKey(f.getKey()))
                .toList();
        TemplateSchema scoped = new TemplateSchema(schema.templateId(), schema.name(),
                schema.version(), annotated, schema.shelfLifeWarnDays());

        LedgerQuality fromExtracted = new ComplianceEvaluator(scoped)
                .evaluate(extracted.getValues(), TODAY);
        LedgerQuality fromExpected = new ComplianceEvaluator(scoped)
                .evaluate(new LinkedHashMap<>(c.expect), TODAY);
        o.levelSame = fromExtracted.getLevel() == fromExpected.getLevel();
        o.levelActual = fromExtracted.getLevel().name();
        o.levelExpected = fromExpected.getLevel().name();
        return o;
    }

    private boolean sameValue(Object expected, Object actual) {
        if (expected == null) {
            return isBlank(actual);
        }
        if (isBlank(actual)) {
            return false;
        }
        if (expected instanceof Number || actual instanceof Number) {
            BigDecimal a = toDecimal(expected);
            BigDecimal b = toDecimal(actual);
            return a != null && b != null && a.compareTo(b) == 0;
        }
        return String.valueOf(expected).replaceAll("\\s", "")
                .equals(String.valueOf(actual).replaceAll("\\s", ""));
    }

    private BigDecimal toDecimal(Object v) {
        try {
            return new BigDecimal(String.valueOf(v).replaceAll("[^0-9.\\-]", ""));
        } catch (Exception e) {
            return null;
        }
    }

    private boolean isBlank(Object v) {
        return v == null || String.valueOf(v).isBlank();
    }

    private String show(Object v) {
        return v == null ? "空" : String.valueOf(v);
    }

    // ---------------------------------------------------------- 报表

    private String report(List<Outcome> outcomes, TemplateSchema schema) {
        int compared = outcomes.stream().mapToInt(o -> o.compared).sum();
        int matched = outcomes.stream().mapToInt(o -> o.matched).sum();
        int expectFilled = outcomes.stream().mapToInt(o -> o.expectFilled).sum();
        int correctlyFilled = outcomes.stream().mapToInt(o -> o.correctlyFilled).sum();
        int expectEmpty = outcomes.stream().mapToInt(o -> o.expectEmpty).sum();
        int falselyFilled = outcomes.stream().mapToInt(o -> o.falselyFilled).sum();
        int basisGiven = outcomes.stream().mapToInt(o -> o.basisGiven).sum();
        int basisDropped = outcomes.stream().mapToInt(o -> o.basisDropped).sum();
        long levelSame = outcomes.stream().filter(o -> o.levelSame).count();
        long degraded = outcomes.stream().filter(o -> o.degraded).count();

        StringBuilder sb = new StringBuilder();
        sb.append("# 抽取评测报告\n\n");
        sb.append("- 模型：deepseek-chat（通过 OpenAI 兼容接口）\n");
        sb.append("- 模板：").append(schema.name()).append(" v").append(schema.version())
                .append("，字段 ").append(schema.fields().size())
                .append(" 个（其中必填 ").append(schema.requiredFields().size()).append(" 个）\n");
        sb.append("- 用例：").append(outcomes.size()).append(" 条，日期基准 ")
                .append(TODAY).append("\n");
        sb.append("- 生成时间：").append(LocalDate.now()).append("\n\n");

        sb.append("## 总体指标\n\n");
        sb.append("| 指标 | 数值 | 说明 |\n|---|---|---|\n");
        sb.append("| 字段准确率 | ").append(pct(ratio(matched, compared)))
                .append(" (").append(matched).append("/").append(compared)
                .append(") | 只看标注过的字段 |\n");
        sb.append("| 有值召回率 | ").append(pct(ratio(correctlyFilled, expectFilled)))
                .append(" (").append(correctlyFilled).append("/").append(expectFilled)
                .append(") | 该抽出来的抽出来了没 |\n");
        sb.append("| 瞎填率 | ").append(pct(ratio(falselyFilled, expectEmpty)))
                .append(" (").append(falselyFilled).append("/").append(expectEmpty)
                .append(") | 该空的却填了——台账里等于造假 |\n");
        sb.append("| 依据编造率 | ").append(pct(ratio(basisDropped, basisGiven)))
                .append(" (").append(basisDropped).append("/").append(basisGiven)
                .append(") | 依据在原文里找不到，已被丢弃 |\n");
        sb.append("| 级别一致率 | ").append(pct(ratio((int) levelSame, outcomes.size())))
                .append(" (").append(levelSame).append("/").append(outcomes.size())
                .append(") | 抽取结果与人工标注判出的合规级别是否一致 |\n");
        sb.append("| 降级次数 | ").append(degraded)
                .append(" | 模型输出无法解析的次数 |\n\n");

        sb.append("## 逐条结果\n\n");
        sb.append("| 用例 | 命中 | 级别 | 备注 |\n|---|---|---|---|\n");
        for (Outcome o : outcomes) {
            sb.append("| ").append(o.id).append(" | ")
                    .append(o.matched).append("/").append(o.compared).append(" | ")
                    .append(o.levelSame ? "一致" : o.levelExpected + "→" + o.levelActual)
                    .append(" | ").append(o.note).append(" |\n");
        }

        List<Outcome> missed = outcomes.stream().filter(o -> !o.misses.isEmpty()).toList();
        if (!missed.isEmpty()) {
            sb.append("\n## 未命中明细\n\n");
            for (Outcome o : missed) {
                sb.append("- **").append(o.id).append("**：")
                        .append(String.join("；", o.misses)).append("\n");
            }
        }
        return sb.toString();
    }

    private double overallAccuracy(List<Outcome> outcomes) {
        return ratio(outcomes.stream().mapToInt(o -> o.matched).sum(),
                outcomes.stream().mapToInt(o -> o.compared).sum());
    }

    private double ratio(int a, int b) {
        return b == 0 ? 1.0 : (double) a / b;
    }

    private String pct(double v) {
        return String.format("%.1f%%", v * 100);
    }

    // ---------------------------------------------------------- 装配

    private Extractor newExtractor(String apiKey) {
        ObjectMapper mapper = new ObjectMapper();
        LlmClient llm = new LlmClient(mapper);
        ReflectionTestUtils.setField(llm, "baseUrl", "https://api.deepseek.com");
        ReflectionTestUtils.setField(llm, "model", "deepseek-chat");
        ReflectionTestUtils.setField(llm, "timeoutSeconds", 60);
        ReflectionTestUtils.setField(llm, "apiKey", apiKey);
        return new Extractor(llm, new PromptBuilder(), mapper);
    }

    /** 从初始化脚本里读模板，避免评测用第二份字段定义 */
    private TemplateSchema loadSchemaFromSeed() throws IOException {
        // 从 classpath 读：target/classes 里已经有这份资源，
        // 比猜 fork 出来的 JVM 的工作目录可靠得多。
        String sql = readResource("/db/data.sql");
        Matcher m = Pattern.compile("(?s)'(\\[\\s*\\{.*?\\}\\s*\\]\\s*)'").matcher(sql);
        assertTrue(m.find(), "没能在 data.sql 里找到模板 fields 数组");
        List<TemplateField> fields = new ObjectMapper()
                .readValue(m.group(1), new TypeReference<>() {
                });
        return new TemplateSchema(1L, "食材采购台账", 1, fields, 3);
    }

    private List<Case> loadCases() throws IOException {
        return new ObjectMapper().readValue(readResource("/eval/cases.json"),
                new TypeReference<>() {
                });
    }

    private String resolveApiKey() throws IOException {
        String env = System.getenv("DEEPSEEK_API_KEY");
        if (env != null && !env.isBlank()) {
            return env;
        }
        // 回退到 application.yml 里的占位符值。仓库默认留空，
        // 此时 apiKey 为空白，评测会被 assumeTrue 跳过（不影响 mvn test）。
        Matcher m = KEY_PATTERN.matcher(readResource("/application.yml"));
        return m.find() ? m.group(1).trim() : null;
    }

    private String readResource(String path) throws IOException {
        try (var in = ExtractionEvalTest.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new IOException("classpath 上找不到 " + path);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    // ---------------------------------------------------------- 数据

    static class Case {
        public String id;
        public String note;
        public List<String> dialogue;
        public Map<String, Object> expect;
    }

    static class Outcome {
        String id;
        String note;
        int compared;
        int matched;
        int expectFilled;
        int correctlyFilled;
        int expectEmpty;
        int falselyFilled;
        int basisGiven;
        int basisDropped;
        boolean levelSame;
        String levelExpected;
        String levelActual;
        boolean degraded;
        final List<String> misses = new ArrayList<>();
    }
}

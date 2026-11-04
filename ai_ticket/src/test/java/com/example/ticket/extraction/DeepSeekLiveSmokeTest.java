package com.example.ticket.extraction;

import com.example.ticket.llm.DeepSeekClient;
import com.example.ticket.llm.LlmClient;
import com.example.ticket.llm.LlmOptions;
import com.example.ticket.llm.LlmProperties;
import com.example.ticket.llm.LlmResponse;
import com.example.ticket.llm.Message;
import com.example.ticket.retrieval.FullInjectRetriever;
import com.example.ticket.template.FieldDef;
import com.example.ticket.template.FieldType;
import com.example.ticket.template.TemplateSchema;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 真实调用 DeepSeek 的冒烟测试。
 *
 * 没有 DEEPSEEK_API_KEY 时自动跳过（Assumptions），不污染 CI。
 *
 * 两个用例刻意用差异极大的 schema：
 *   电商售后   —— 订单中心，有布尔/枚举/图片，无 date
 *   IT 设备报修 —— 设备中心，有 date，必填项在部分场景天然拿不到
 * 这正是「一套 prompt 适配任意 schema」的验证点：
 * 若同一个 PromptBuilder 能把两者都跑通，通用性才算成立。
 *
 * 只断言「结构」，不断言「内容对错」——内容质量会随模型版本波动，
 * 写进断言必然变成 flaky 测试。内容质量交给核验层与人工抽检。
 */
class DeepSeekLiveSmokeTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 22);

    private final ObjectMapper mapper = new ObjectMapper();

    // ---------------------------------------------------------- schemas

    private TemplateSchema ecommerceSchema() {
        return new TemplateSchema(
                "电商售后工单", 3,
                "你是服装店的售后助手，只负责把顾客的诉求整理成工单。"
                        + "语气克制，不承诺赔付、不承诺到货时间。",
                "【退换货政策】签收 7 天内可无理由退换，商品需不影响二次销售。"
                        + "吊牌已剪、已洗涤、已穿着外出的不支持无理由退换。"
                        + "【尺码表】S 适合 155-165 / M 适合 165-170 / L 适合 170-178",
                List.of("批准换货", "批准退货", "拒绝", "要求补证", "转售后专岗"),
                List.of(
                        new FieldDef("order_id", "订单号", FieldType.TEXT, true, null, "^\\d{8,20}$"),
                        new FieldDef("reason", "原因", FieldType.ENUM, true,
                                List.of("尺码不合适", "质量问题", "发错货", "其他"), null),
                        new FieldDef("worn", "是否已穿着或洗涤", FieldType.BOOLEAN, false, null, null),
                        new FieldDef("size_wanted", "希望换成的尺码", FieldType.TEXT, false, null,
                                "^(XS|S|M|L|XL|XXL)$"),
                        new FieldDef("evidence", "商品问题照片", FieldType.IMAGE, false, null, null)));
    }

    private TemplateSchema itSchema() {
        return new TemplateSchema(
                "IT 设备报修工单", 1,
                "你是公司 IT 服务台的接单助手，只做信息整理，不判断责任、不承诺修复时间。",
                "【处理流程】硬件故障转技术支持；账号权限问题转信息科；"
                        + "网络问题先远程排查，再决定是否上门。"
                        + "【设备编号规则】办公电脑形如 PC-2041，打印设备形如 PR-0071，"
                        + "会议室无固定设备编号。",
                List.of("指派技术支持", "远程处理", "上门维修", "更换设备", "拒绝"),
                List.of(
                        new FieldDef("device_no", "设备编号", FieldType.TEXT, true, null,
                                "^(PC|PR)-\\d{4}$"),
                        new FieldDef("fault_type", "故障类型", FieldType.ENUM, true,
                                List.of("硬件故障", "软件故障", "网络问题", "账号权限", "其他"), null),
                        new FieldDef("impact_scope", "影响范围", FieldType.ENUM, true,
                                List.of("仅本人", "整个部门", "全公司"), null),
                        new FieldDef("urgent", "是否影响当前正在进行的业务",
                                FieldType.BOOLEAN, false, null, null),
                        new FieldDef("first_seen", "问题最早出现日期",
                                FieldType.DATE, false, null, null),
                        new FieldDef("screenshot", "报错截图", FieldType.IMAGE, false, null, null)));
    }

    // ---------------------------------------------------------- 共用流程

    private LlmClient client() {
        LlmProperties props = new LlmProperties();
        props.setBaseUrl("https://api.deepseek.com");
        props.setApiKey(System.getenv("DEEPSEEK_API_KEY"));
        props.setModel("deepseek-chat");
        props.setTimeoutSeconds(60);
        props.setJsonMode(true);
        return new DeepSeekClient(props, mapper);
    }

    /** 跑一次完整链路，落盘 prompt 与原始输出，并断言结构 */
    private void runExtraction(TemplateSchema schema, String dialogue, String tag,
                               int expectedValueCount) throws Exception {
        String key = System.getenv("DEEPSEEK_API_KEY");
        Assumptions.assumeTrue(key != null && !key.isBlank(),
                "未设置 DEEPSEEK_API_KEY，跳过真实调用");

        PromptBuilder builder = new PromptBuilder(
                new FullInjectRetriever(), new FewShotGenerator(mapper));
        List<Message> messages = builder.build(schema, dialogue, TODAY);
        LlmResponse response = client().chat(messages, LlmOptions.defaults());

        Path dir = Path.of("target", "module2");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve(tag + "-prompt.txt"),
                messages.get(0).content(), StandardCharsets.UTF_8);
        Files.writeString(dir.resolve(tag + "-output.txt"),
                response.content(), StandardCharsets.UTF_8);

        System.out.printf("[%s] 实际模型=%s 耗时=%dms prompt=%d completion=%d%n",
                tag, response.model(), response.latencyMs(),
                response.promptTokens(), response.completionTokens());

        Map<String, Object> obj = mapper.readValue(response.content(),
                new TypeReference<Map<String, Object>>() {
                });
        for (String k : new String[]{"values", "basis", "suggestion", "summary"}) {
            assertTrue(obj.containsKey(k), "[" + tag + "] 输出缺少字段 " + k);
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> values = (Map<String, Object>) obj.get("values");
        assertNotNull(values, "[" + tag + "] values 为空");
        for (FieldDef f : schema.getFields()) {
            assertTrue(values.containsKey(f.getKey()),
                    "[" + tag + "] values 缺少字段 " + f.getKey());
        }
        assertEquals(expectedValueCount, values.size(),
                "[" + tag + "] values 字段数与 schema 不一致");

        // image 字段必须为 null —— 设计里写死的规则
        for (FieldDef f : schema.getFields()) {
            if (f.getType() == FieldType.IMAGE) {
                assertNull(values.get(f.getKey()),
                        "[" + tag + "] image 字段不应被填充");
            }
        }

        // 依据必须是原文片段：只打印不匹配项，不做断言（避免 flaky）
        @SuppressWarnings("unchecked")
        Map<String, Object> basis = (Map<String, Object>) obj.get("basis");
        if (basis != null) {
            basis.forEach((k, v) -> {
                if (v != null && !dialogue.contains(String.valueOf(v))) {
                    System.out.println("[" + tag + "][提醒] basis." + k
                            + " 不是原文片段: " + v);
                }
            });
        }
        System.out.println("[" + tag + "] values = "
                + mapper.writeValueAsString(values));
    }

    // ---------------------------------------------------------- 用例

    @Test
    @DisplayName("电商售后 schema：信息完整的一段对话")
    void ecommerceSchemaExtraction() throws Exception {
        runExtraction(ecommerceSchema(),
                "您好，我上周买的卫衣订单号 2026100812345678，收到的M码太大了，"
                        + "我平时穿S的，想换成S码。衣服吊牌还没剪，也没穿过。",
                "ecommerce", 5);
    }

    @Test
    @DisplayName("IT 报修 schema：同一套管道，结构完全不同的字段")
    void itSchemaExtraction() throws Exception {
        runExtraction(itSchema(),
                "我这边笔记本开不了机，按电源键没反应，设备编号 PC-2031，"
                        + "今天早上开始的。整个部门今天要用它做汇报，比较急。",
                "it", 6);
    }

    @Test
    @DisplayName("IT 报修 schema：必填项天然拿不到时，模型应如实留空")
    void itSchemaWithUnsatisfiableRequired() throws Exception {
        runExtraction(itSchema(),
                "三楼会议室这边的 Wi-Fi 全部连不上，我们组 8 个人都没网，从下午两点开始。",
                "it-missing", 6);
    }

    // ---------------------------------------------------------- 失败路径

    private Extractor extractor() {
        return new Extractor(
                new PromptBuilder(new FullInjectRetriever(), new FewShotGenerator(mapper)),
                client(),
                new JsonOutputParser(mapper),
                new JsonSchemaValidator(),
                new RetryPolicy(3),
                mapper);
    }

    @Test
    @DisplayName("pattern 与原文冲突时：不产生非法值、不死循环、不抛异常")
    void unsatisfiablePatternDoesNotBreakPipeline() throws Exception {
        String key = System.getenv("DEEPSEEK_API_KEY");
        Assumptions.assumeTrue(key != null && !key.isBlank(),
                "未设置 DEEPSEEK_API_KEY，跳过真实调用");

        // 设备编号在对话里是 PC-2031，但把 pattern 写成「4 位数字」，
        // 模型无法同时满足「忠于原文」与「符合格式」。
        //
        // 第一次跑这条用例时，断言「必然降级」失败了 —— 因为模型选择了
        // 输出 null（规则 1：无法确定就填 null），而不是硬填一个违规值。
        // 这说明 prompt 规则在起作用，也说明「降级」不能用真实模型稳定复现。
        //
        // 所以这里断言的是更本质的性质：
        //   要么降级，要么给出合法值（含 null），绝不允许非法值漏出去。
        // 降级路径本身由 ExtractorTest 的脚本化单元测试覆盖。
        TemplateSchema impossible = new TemplateSchema(
                "严格格式模板", 1, "你是接单助手", "无",
                List.of("接收", "拒绝"),
                List.of(new FieldDef("device_no", "设备编号", FieldType.TEXT,
                        true, null, "^\\d{4}$")));

        ExtractionResult result = extractor().extract(impossible,
                "设备编号 PC-2031 的机器开不了机了。");

        String value = String.valueOf(result.getValues().get("device_no"));
        String report = "attempts=%d degraded=%s value=%s violations=%s"
                .formatted(result.getAttempts(), result.isDegraded(), value,
                        result.getViolations());
        System.out.println("[degrade] " + report);
        Path dir = Path.of("target", "module2");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("degrade-report.txt"), report,
                StandardCharsets.UTF_8);

        assertTrue(result.getAttempts() <= 3, "尝试次数必须封顶");
        Object raw = result.getValues().get("device_no");
        if (raw != null) {
            assertTrue(java.util.regex.Pattern.matches("^\\d{4}$", String.valueOf(raw)),
                    "漏出了不符合 pattern 的值: " + raw);
        }
    }
}
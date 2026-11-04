================================================================
AI 客服工单系统 · 模块划分与技术规格
最后更新：2026-10-20
文档定位：本文件管「怎么写」，不管「为什么这么设计」。
         设计理由一律在 idea.txt，此处不复述，只给结构与契约。
================================================================


----------------------------------------------------------------
【零、文档分工】
----------------------------------------------------------------

  idea.txt          设计思路（为什么这样设计、边界在哪）
  design-modules.md 技术规格（分层、包结构、类职责、表、接口）

一份文件讲「为什么」，一份文件讲「怎么写」。
混在一起的结果是：改代码时要翻设计哲学，改设计时要翻建表语句。


----------------------------------------------------------------
【一、分层与依赖方向】
----------------------------------------------------------------

四层架构在代码里的落地，以及唯一允许的依赖方向：

    一、模板层  template
        领域配置包。只管 schema 的存、取、版本。
    二、抽取层  extraction + llm
        模板 → prompt → LLM → 四字段输出 → 校验重试。
    三、核验层  quality
        纯函数。三维度核验 + 分级。
    四、工单层  ticket + chat
        编排、落库、状态机、接口。

依赖规则（从下往上，禁止反向）：

    chat  →  extraction  →  llm
      │          │
      │          └→  quality
      │                 ↑
      └──→  ticket  ────┘
              ↑
         template

三条硬约束：

    1. quality 包不得引用任何 Spring 注解、不访问数据库、
       不调用 LlmClient。它是一个可以脱离整个应用运行的纯函数库。
       —— 这是唯一能写完整单元测试的核心逻辑，必须保持纯净。

    2. llm 包不知道 schema 的存在。
       它只负责「发一组 message，收一段文本」。
       知道 schema 的只有 extraction。

    3. Controller 不写业务。
       所有分支判断（追问还是出单）只出现在 ConversationOrchestrator 一处。

反例（不允许出现）：
    ✗ QualityEvaluator 里注入 TicketMapper
    ✗ TicketService 里调用 LlmClient
    ✗ ChatController 里写 if (missingRequired.isEmpty()) ...
    ✗ LlmClient 里出现 FieldDef / TemplateSchema


----------------------------------------------------------------
【二、后端包结构】
----------------------------------------------------------------

com.example.ticket
│
├── TicketApplication.java
│
├── common/                        通用基础设施
│     ApiResponse.java             统一响应包装
│     BizException.java            业务异常
│     GlobalExceptionHandler.java
│     JsonUtil.java                Jackson 封装（统一 null 处理）
│
├── template/                      一、模板层
│     TemplateController.java      REST
│     TemplateService.java         整体提交、版本递增
│     TemplateMapper.java          MyBatis-Plus BaseMapper
│     Template.java                实体
│     TemplateSchema.java          模板的运行时视图（供抽取层使用）
│     FieldDef.java                字段定义
│     FieldType.java               枚举：6 种类型
│
├── llm/                           可替换叶子
│     LlmClient.java               接口
│     DeepSeekClient.java          实现（OpenAI 兼容协议）
│     Message.java
│     LlmResponse.java
│
├── retrieval/                     可替换叶子
│     Retriever.java               接口
│     FullInjectRetriever.java     返回模板 knowledge 全文
│
├── extraction/                    二、抽取层（核心）
│     PromptBuilder.java           模板 + few-shot → prompt  ★
│     FewShotGenerator.java        按字段类型自动生成示例   ★
│     Extractor.java               调 LlmClient + 校验 + 重试
│     ExtractionResult.java        values / basis / suggestion / summary
│     JsonSchemaValidator.java     JSON 结构校验
│     RetryPolicy.java             失败重试的判定与次数
│
├── quality/                       三、核验层（纯函数，无框架依赖）
│     QualityEvaluator.java        入口：三维度核验 + 分级  ★
│     MissingAnalyzer.java         从 values 推导缺失并分必填/选填
│     FaithfulnessChecker.java     basis 三校验
│     ConsistencyChecker.java      通用规则冲突
│     QualityReport.java           返回结构
│     QualityLevel.java            红 / 黄 / 绿
│     FieldPatternValidator.java   pattern / date / type 校验
│
├── chat/                          四、编排层
│     ChatController.java          POST（无流式）
│     ConversationOrchestrator.java  ★ 全系统唯一的业务分支
│     SessionStore.java            会话读写
│     FollowUpBuilder.java         追问话术（由 label 拼，不用模型）
│     ChatSession.java             实体
│     ChatMessage.java             实体
│
├── ticket/                        四、产出物
│     TicketController.java
│     TicketService.java           状态机、指派、决策
│     TicketMapper.java            MyBatis-Plus BaseMapper
│     Ticket.java                  实体
│     TicketFactory.java           由抽取结果 + 核验结果组装工单
│     TicketStatus.java            枚举（含「信息不全」）
│
└── config/
      WebConfig.java
      LlmConfig.java
      JacksonConfig.java

★ 标记的三个类是整个项目的技术核心：
    PromptBuilder      决定「一套 prompt 能不能适配任意 schema」
    QualityEvaluator   决定「分级可不可信」
    ConversationOrchestrator  决定「追问闭环对不对」


----------------------------------------------------------------
【三、核心类职责】
----------------------------------------------------------------

▍LlmClient（接口，可替换点）

    public interface LlmClient {
        LlmResponse chat(List<Message> messages, Options options);
    }

    做什么：发 message，收文本
    不做什么：不知道 schema、不解析 JSON、不重试

▍Extractor

    做什么：模板 + 对话 → 四字段结果（含一次校验与重试）
    不做什么：不判断缺失、不评分、不落库、不决定追问

    ExtractionResult extract(TemplateSchema schema, List<Message> dialogue);

    ExtractionResult 的结构：
        Map<String,Object> values
        Map<String,String> basis
        String suggestion       可能为 null
        String summary

    重试条件（RetryPolicy）：
        JSON 解析失败
        enum 值越界
        pattern 不匹配
        必填字段在 values 里完全缺席（不是 null，是缺键）
    → 最多重试 2 次，仍失败则降级为「全员缺失」，交由核验层判红

▍PromptBuilder 与 FewShotGenerator

    做什么：模板 → system prompt（一次拼装，无状态）
    不做什么：不调模型、不解析输出

    拼装顺序（与 idea.txt 7.5 一致）：
        1 role
        2 knowledge（Retriever 全文注入）
        3 decision_options（禁止自创）
        4 fields 清单（key / label / type / 必填或选填 / 枚举候选）
        5 输出契约（四字段 JSON）
        6 硬规则（禁编造、enum 限值、basis 必填）
        7 few-shot（FewShotGenerator 自动生成）

    FewShotGenerator 是最值得单独抽出来的类：
        按字段类型机械填一个合法值 → 拼成示例 JSON。
        它让「新模板零调试」成为可能，是通用性的技术前提。

▍MissingAnalyzer（核验层）

    做什么：schema + values → 缺失清单（分必填/选填）
    不做什么：不调用模型、不看对话

    核心算法（两种输入完全一致）：
        nullFields       = values 中值为 null 的 key
                         + schema 中不存在于 values 的 key
        missingRequired  = nullFields ∩ 必填
        missingOptional  = nullFields ∩ 选填

    为什么不用模型输出的 missing_slots：
        实测会漏项。values 是事实，模型的自述不是。

▍QualityEvaluator（核验层，纯函数）

    做什么：三维度核验 + 分级 + 生成原因句子
    不做什么：不访问数据库、不依赖 Spring、不调模型

    QualityReport evaluate(TemplateSchema schema,
                           Map<String,Object> values,
                           Map<String,String> basis,
                           String dialogueText,
                           LocalDateTime now);
                             ↑ 全部确定性输入，无注入的 Mapper

    QualityReport 的结构：
        QualityLevel level            红 / 黄 / 绿
        DecisionPart decision         可决策性：blocking[] + degrading[]
        FaithfulnessPart faithfulness 忠实性：checkItems[] + coverage
        ConsistencyPart consistency   自洽性：violations[] + advisories[]
        Metrics metrics               三个比率（见下）
        List<String> messages         给人看的原因句子

    Metrics（完备性的三个比率）：
        fillRate          已填字段 / 字段总数
        requiredRate      已填必填 / 必填总数
        optionalCoverage  已填选填 / 选填总数
        basisCoverage     有依据的非空字段 / 非空字段

    合成顺序（门槛优先）：
        可决策性有阻断 → 红（不再看后两维）
        忠实性有降级   → 黄
        自洽性有通用规则命中 → 黄
        剩余           → 绿
    自洽性的 advisories 只进 messages，不改变 level。

    这个类必须 100% 可单测：
        输入是纯数据，输出是纯数据，没有任何外部依赖。

▍ConversationOrchestrator（全系统唯一的业务分支）

    做什么：编排一次对话轮次，决定「追问」还是「出单」
    不做什么：不做 prompt（PromptBuilder）、不评分（QualityEvaluator）、
              不落库（TicketFactory / Mapper）

    OrchestratorResult handle(String sessionId, String userMessage);

    流程：
        1 追加 user 消息到会话
        2 Extractor.extract(schema, 全量对话)
        3 合并到会话的 collectedValues / collectedBasis
        4 MissingAnalyzer.analyze → missingRequired
        5 QualityEvaluator.evaluate
        6 判定：
             missingRequired 非空 且 followupCount < 2
                 → FollowUpBuilder 生成追问，followupCount++
                 → 返回 ASK
             missingRequired 非空 且 followupCount >= 2
                 → TicketFactory 出单，status=信息不全，level=红
                 → 返回 TICKET
             其余
                 → TicketFactory 出单
                 → 返回 TICKET
        7 suggestion 的放行：
             missingRequired 非空 → 强制置空（即使模型给了也不采用）

    全局只有这里的 if 是业务逻辑，控制器不许复制它。

▍FollowUpBuilder

    做什么：根据缺失字段的 label 拼一句追问
    不做什么：不调用模型

    理由：追问只需要问清「缺什么」，是确定性拼装。
         交给模型只会引入不必要的波动与幻觉。
    示例：「为了帮您处理，还需要确认：订单号、是否已穿着。」


----------------------------------------------------------------
【四、数据表】
----------------------------------------------------------------

MySQL 8，utf8mb4。四张表。

▍template

    CREATE TABLE template (
      id              BIGINT PRIMARY KEY AUTO_INCREMENT,
      name            VARCHAR(100)  NOT NULL,
      version         INT           NOT NULL DEFAULT 1,
      role_text       VARCHAR(500)  NOT NULL,
      knowledge       MEDIUMTEXT,
      decision_options JSON         NOT NULL,
      fields          JSON          NOT NULL,
      enabled         TINYINT       NOT NULL DEFAULT 1,
      created_at      DATETIME      NOT NULL,
      updated_at      DATETIME      NOT NULL
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

    fields 是 json 数组，元素形如：
      {"key":"order_id","label":"订单号","type":"text",
       "required":true,"options":null,"pattern":"^\\d{8,20}$"}

    只保留当前版本的记录，version 只用于标记「改过几次」。
    历史版本的渲染靠工单里的 schema_snapshot，不依赖模板历史表。

▍chat_session

    CREATE TABLE chat_session (
      id                BIGINT PRIMARY KEY AUTO_INCREMENT,
      template_id       BIGINT       NOT NULL,
      template_version  INT          NOT NULL,
      state             VARCHAR(20)  NOT NULL,  -- CHATTING / TICKETED
      followup_count    INT          NOT NULL DEFAULT 0,
      collected_values  JSON,
      collected_basis   JSON,
      ticket_id         BIGINT,
      created_at        DATETIME     NOT NULL,
      updated_at        DATETIME     NOT NULL
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

    collected_values 存的是「跨轮次累积」的抽取结果。
    每轮新抽取会覆盖非空值、保留已有值（非空优先）。
    —— 为什么需要累积：顾客第二次补充订单号时，
       模型未必会把上一轮的原因一起复述出来。

▍chat_message

    CREATE TABLE chat_message (
      id          BIGINT PRIMARY KEY AUTO_INCREMENT,
      session_id  BIGINT      NOT NULL,
      role        VARCHAR(20) NOT NULL,          -- user / assistant / system
      content     TEXT        NOT NULL,
      created_at  DATETIME    NOT NULL,
      KEY idx_session (session_id, id)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

▍ticket

    CREATE TABLE ticket (
      id                BIGINT PRIMARY KEY AUTO_INCREMENT,
      template_id       BIGINT      NOT NULL,
      template_version  INT         NOT NULL,
      schema_snapshot   JSON        NOT NULL,   -- 渲染唯一依据
      values_data       JSON        NOT NULL,   -- 避免 values 关键字
      basis             JSON,
      summary           TEXT,
      suggestion        VARCHAR(100),
      quality_level     VARCHAR(10) NOT NULL,   -- RED / YELLOW / GREEN
      quality_detail    JSON        NOT NULL,   -- 三维度明细 + 比率 + messages
      status            VARCHAR(20) NOT NULL,   -- 含 INFO_INCOMPLETE
      assignee          VARCHAR(50),
      decision          VARCHAR(100),
      session_id        BIGINT,
      needs_clarify     TINYINT     NOT NULL DEFAULT 0,  -- 生产环境埋点
      decision_time_ms  INT,
      created_at        DATETIME    NOT NULL,
      decided_at        DATETIME,
      KEY idx_status (status, created_at),
      KEY idx_template (template_id, created_at)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

    三个必须记住的字段：
        schema_snapshot  渲染工单时用它，永不回查当前模板
        quality_detail   存三维度明细，不只是结论
        needs_clarify    人工点「需联系顾客」，这就是回问率的埋点

    状态机（固定）：
        PENDING → ASSIGNED → PROCESSING → RESOLVED / CLOSED
        INFO_INCOMPLETE 是出单时的初始状态，同样可被指派与处理


▍MyBatis-Plus 映射要点

    命名：接口叫 XxxMapper，继承 BaseMapper<Xxx>，
          加 @Mapper 注解让它被自动扫描。
          ★ 启动类刻意不加 @MapperScan：它会按包扫描所有接口，
            把 LlmClient、Retriever 这类业务接口误注册成 Mapper。
          不写 XML，复杂查询用 LambdaQueryWrapper。

    ★ JSON 列一律用 String 承载，反序列化集中在 Service。
      这是对初版方案（JacksonTypeHandler）的有意修正，原因有三：

        1. 用 TypeHandler 必须配 @TableName(autoResultMap = true)，
           忘记开就会在查询时抛 ClassCastException，
           而报错现象看起来像 JSON 解析问题，排查方向容易跑偏。
        2. 每个 JSON 字段都要单独声明 typeHandler，不会全局生效。
        3. 泛型集合（List<FieldDef>、Map<String,Object>）走
           TypeHandler 时对字段泛型信息的依赖比较脆。

      改用 String 之后，实体保持"哑对象"，转换只有一处：

          Template 实体
            roleText               → role_text
            decisionOptionsJson    → decision_options
            fieldsJson             → fields
                ↓ TemplateService.toSchema(...)
            TemplateSchema（运行时视图，抽取层与核验层只认它）

      代价是每次读模板多一次 Jackson 解析——模板数量以个位数计，
      可以忽略。收益是所有类型转换都可见、可断点、可单测。

    坑（真实踩过）：实体属性名与列名不一致时必须显式注解，
        否则 MP 会把 fieldsJson 映射成 fields_json 列。

            @TableField("role_text")     private String roleText;
            @TableField("decision_options") private String decisionOptionsJson;
            @TableField("fields")        private String fieldsJson;

    其它：
        LocalDateTime 无需额外配置，MP 自带映射。
        字段名 values_data 是为了避开 SQL 保留字 values。
        分页可选：PaginationInnerInterceptor 五行配置；
        工单列表数据量小，也可以直接 limit。


----------------------------------------------------------------
【五、接口契约】
----------------------------------------------------------------

统一响应：
    { "code": 0, "message": "ok", "data": {...} }
    code != 0 表示业务错误。

▍模板

    GET    /api/templates                 列表（含 id / name / version）
    POST   /api/templates                 新建
    GET    /api/templates/{id}            详情（完整 json）
    PUT    /api/templates/{id}            整体提交，version + 1
    POST   /api/templates/{id}/preview    试抽，不落库  ★

    —— PUT 是整体覆盖，不提供字段级 CRUD。
       前端维护一份本地 json，改完整体提交。
       理由：模板在语义上是一个整体，字段级 CRUD 会引入
       一整套「部分更新」的状态问题，收益远小于成本。

▍试抽接口（本设计里最值得优先实现的接口）

    POST /api/templates/{id}/preview
    Req:  { "dialogue": "顾客原话……" }
    Req 的 dialogue 会原样回显，方便前端对照。
    Res:  { "dialogue": "...",
            "values": {...}, "basis": {...},
            "suggestion": "...", "summary": "...",
            "quality": { 完整的 QualityReport：
                         level / decision / faithfulness /
                         consistency / metrics / messages } }

    注意 quality 返回的是**完整质量报告**，不只是 level + messages。
    模板作者需要看到「缺的是哪几项必填」「哪几个字段没有依据」，
    只给一个颜色是没用的。

    它做的是一次完整的抽取 + 核验，但不写库。
    用途：模板作者编辑字段时立刻看到「我设计的字段
          在这段对话上抽出来长什么样、是什么颜色」。

    这个接口把「字段设计」从盲猜变成可试错，
    是 idea.txt 6.2 三个检验能真正落地的前提。

▍对话

    POST /api/chat/sessions               Req: {templateId} → {sessionId, greeting}
    POST /api/chat/sessions/{id}/messages Req: {content} → 一次 JSON 响应
    POST /api/chat/sessions/{id}/confirm  Req: {summary?} → 确认后入队
                                          仅「信息不全」的工单会用到
    GET  /api/chat/sessions/{id}/status   工单状态回执
                                          未出单→null；状态没变→changed=false

    POST /api/auth/login                  人工端登录（写 session cookie）
    GET  /api/auth/me                     当前登录者；未登录→401
    POST /api/auth/logout                 退出

    鉴权范围：AuthInterceptor 只保护 /api/tickets/**、/api/templates/**。
    /api/chat/** 不保护——顾客端要用。
    GET  /api/chat/sessions/{id}/messages 历史消息（用于刷新恢复）

    一轮请求的响应可能是三种形态之一：

    {
      "replyType": "ASK",
      "question": "为了帮您处理，还需要确认：订单号、是否已穿着。",
      "followupCount": 1
    }

    {
      "replyType": "TICKET",
      "ticketId": 123,
      "level": "YELLOW",
      "messages": ["2 个字段无原文依据：影响范围、故障类型"]
    }

    为什么不做流式：
        实测端到端 0.63~1.30s，平均 0.95s；且模型输出是结构化 JSON，
        不是给人读的长文本；顾客看到的两句话（追问、出单提示）
        都由代码生成，瞬时可得。整条流式管道没有可服务的东西。
        详见 tech-stack.md §六。

    前端只需要一个 loading 态。1 秒以内，一个转圈就够。

▍工单

    GET   /api/tickets?status=&level=&page=   列表
    GET   /api/tickets/{id}                   详情（含 schema_snapshot 与 basis）
    PATCH /api/tickets/{id}                   状态 / 指派 / 决策 / needs_clarify

    没有 /conversation 端点。
    原始对话用详情里的 sessionId 调
    GET /api/chat/sessions/{sessionId}/messages 取回。
    这样切是为了不产生 ticket → chat 的包依赖——
    依赖方向是 chat → ticket（编排层触发落库），反向会成环。

    PATCH 请求体只允许这四个字段，其它一律忽略：
        { "status": "...", "assignee": "...",
          "decision": "...", "needsClarify": true }


----------------------------------------------------------------
【六、前端结构】
----------------------------------------------------------------

两个端，一套依赖（vite 多页：index.html + agent/index.html）

src/
├── shared/                两端共用
│     api/  http.js（统一请求包装）/ chat / ticket / template
│     components/  DynamicForm ★ / QualityBanner ★ / FieldEditTable
│     styles/base.css     基础样式与 CSS 变量
├── user/                  用户端（无路由，顾客只有一条路径）
│     main.js  App.vue  views/ChatView.vue
└── agent/                 人工端
      main.js  App.vue  router/index.js（hash）
      views/ TicketList / TicketDetail / TemplateList / TemplateEditor

为什么这样分：
    顾客只需要「选类型 → 对话 → 拿到工单号」，
    不该看到工单列表、字段定义、内部质量分级；
    人工端才需要批阅与配置。
    两端的信息边界靠接口也卡死：用户端只能调
    /api/chat/templates、/api/chat/sessions*，
    字段定义与分级根本不下发。

两个复用点（省时间的关键）：

    DynamicForm.vue
        同时服务两处：模板编辑器的实时预览、工单详情的渲染。
        写一次用两处，否则两套渲染逻辑必然不同步。

    QualityBanner.vue
        级别提示必须在每个出现工单的地方一致，
        包括列表页的每一行和详情页顶部。

一条 UI 规则（来自 design 文档 9.9）：
    basis 必须始终可见，红黄绿一视同仁。
    不要做成「只有黄红才展开」——那会让误读永远无法被发现。


----------------------------------------------------------------
【七、模块边界：每个模块「不做什么」】
----------------------------------------------------------------

这一节是为了防止模块膨胀。每加一个功能，先看它属于哪一行。

  template     schema 的存取与版本        不做抽取、不评分
  llm          一次 HTTP 往返             不解析 JSON、不重试
  retrieval    返回知识文本               不做切分与召回
  extraction   模板 → prompt → 四字段     不判缺失、不落库
  quality      三维度核验与分级           不访问 DB、不调模型
  chat         编排与接口               不写视图、不评分
  ticket       状态机与决策记录           不调模型

三个最容易被越界侵蚀的地方：

    1. ticket 模块里加「AI 建议摘要」→ 等于把模型引进工单层，
       破坏了「工单层只读已定结果」的性质。禁止。
    2. quality 模块里注入 Mapper 去查订单 → 模块不可单测了。禁止。
    3. chat 模块里加「顺便处理一下工单状态」→ 编排边界消失。禁止。


----------------------------------------------------------------
【八、可替换点与扩展点】
----------------------------------------------------------------

当前实现 vs 未来实现，接口不变：

    LlmClient      DeepSeekClient        Qwen / GLM / 本地模型
    Retriever      FullInjectRetriever   VectorRetriever

判断标准（idea.txt §10.1）：
    要换掉某一组件时，需要改几个文件？
    改 1 个 → 分层成立；改 20 个 → 没有分层。

值得注意的是：这套设计里没有为以下东西留抽象，
因为它们是明确不做的（idea.txt §11）：
    向量库、文档解析、对象存储、消息队列、工作流引擎。
没有抽象本身就是一种设计决定。


----------------------------------------------------------------
【九、测试策略：哪些模块能测，怎么测】
----------------------------------------------------------------

不是所有模块都值得写测试。按「确定性」分层：

    quality 全家           完美   纯单测，无 mock，构造输入
    MissingAnalyzer        完美   纯单测
    FewShotGenerator       完美   纯单测（快照测试）
    PromptBuilder          完美   纯单测（断言拼装顺序）
    FieldPatternValidator  完美   纯单测
    Extractor              较好   mock LlmClient，断言重试
    ChatService / 编排     一般   mock Extractor + Quality
    Controller             一般   MockMvc
    DeepSeekClient         不测   外部依赖，联调时手工验证

值得写满测试的只有左边那五个——它们全是纯函数，
不依赖数据库、不依赖网络、不依赖 Spring 上下文。
这一点是 quality 包「不许引用 Spring」的直接原因。


----------------------------------------------------------------
【十、并行开发的契约边界】
----------------------------------------------------------------

两人同时开工时，唯一的冲突点是字段命名。
解决办法：先产出五份契约文件，双方都按它写（详见 mock/README.md）。

    mock/template.json            电商售后模板（主模板，version 3）
    mock/template-it.json         IT 报修模板（第二模板，验证通用性）
    mock/extraction-result.json   抽取结果（四字段）
    mock/ticket.json              两张工单：一张黄、一张红
    mock/preview-response.json    试抽接口的完整响应 + 源对话

约定：

    前端只依赖这五份 mock 的结构，不等后端接口。
    后端改字段名 = 破坏契约，必须同步改 mock 并通知对方。

三个契约锚点（改动代价最高的地方，一旦定下不要动）：

    1. fields 数组里每个元素的 key / type / required / options / pattern
    2. ExtractionResult 的四个字段名
    3. QualityReport 的 level 取值（RED / YELLOW / GREEN）

这三处一动，前后端都要改，数据库也要迁移。


----------------------------------------------------------------
【十一、实现顺序的技术依据】
----------------------------------------------------------------

不是排期，是依赖顺序——某个东西没做完，另一个做不了：

    1. template 表 + TemplateSchema
       因为抽取层的入参就是它，没它无法写 PromptBuilder。

    2. PromptBuilder + FewShotGenerator + LlmClient
       因为这是整条链路的技术风险所在，必须最先被验证。

    3. ExtractionResult + JsonSchemaValidator + RetryPolicy
       把「模型不可靠」这件事在代码里兜住。

    4. MissingAnalyzer + QualityEvaluator
       纯函数，可以在没有数据库、没有界面的情况下完全做完。

    5. ChatSession + chat_message + Orchestrator
       到了这一步才需要持久化，因为前面全是纯函数。

    6. ticket 表 + TicketFactory + 状态机
       编排跑通之后才有工单可存。

    7. 前端：DynamicForm 优先于一切
       因为模板编辑器与工单详情都依赖它，晚做会返工。

    8. preview 接口
       它同时是调试工具和产品功能，越早做越省事。

注意第 4 步的位置：它是纯函数，不需要等数据库、
不需要等前端、不需要等模型稳定——
所以它是两人中「另一个人」最理想的切入点。





----------------------------------------------------------------
【十二、实现进度】
----------------------------------------------------------------

按 §十一 的依赖顺序推进。每完成一个模块，
必须做到「可编译 + 有测试 + 通过」。

  模块 1  工程骨架 + template 模块                  ✅ 完成
          pom / application.yml / db-schema.sql
          common: ApiResponse, BizException, GlobalExceptionHandler
          template: FieldType, FieldDef, TemplateSchema, Template,
                    TemplateMapper, TemplateService, TemplateController
          测试 16 个：字段 JSON 映射 5 + 校验规则 7 + 接口契约 4

          已验证（真实环境）：
            mvn test 全绿（JDK 23 + Boot 3.4.8 + MP 3.5.7）
            schema.sql 在 MySQL 8.0.41 执行成功，4 张表 + 初始模板
            GET  /api/templates      返回 2 条，中文正常
            GET  /api/templates/1    字段结构、type 小写、pattern 均正确
            POST 缺 required 的字段  → 400 且报文准确
            POST mock/template-it   → id=2, version=1, 6 字段
            PUT  同一模板            → version=2（版本递增正确）

          实测发现并修正的两处问题（记录在案）：
            1. mock 模板里写的是 decision_options，
               而接口用 camelCase decisionOptions → 已统一为 camelCase，
               并在 mock/README.md 加了命名约定一节。
            2. 用 PowerShell 的 Set-Content -Encoding UTF8 写文件会带 BOM，
               会让 JSON 解析失败（python 报 Unexpected UTF-8 BOM）。
               → 改动 JSON 文件一律用不带 BOM 的写法。

  模块 2  PromptBuilder + FewShotGenerator + LlmClient   ✅ 完成
          llm: Message / LlmOptions / LlmResponse / LlmClient /
               LlmProperties / DeepSeekClient（java.net.http，无第三方）
          retrieval: Retriever / FullInjectRetriever
          extraction: FewShot / FewShotGenerator / PromptBuilder
          测试 8 个：few-shot 生成 6 + prompt 拼装 7（纯单测）
                    + 真实调用冒烟 3（无 DEEPSEEK_API_KEY 时自动跳过）

          已验证（真实 API）：
            同一套 PromptBuilder 跑通三个用例：
              电商 schema / IT schema / IT schema 缺必填项
            抽取结果与 mock/extraction-result.json 逐字段一致
            IT 用例中相对时间换算正确（"今天早上" → 2026-10-22）
            会议室 Wi-Fi 用例 device_no 如实为 null（不编造）
            全部 basis 都是对话原文的子串

          prompt 与原始输出落盘在 target/module2/，便于逐字核对

          实测发现并修正的两处问题：
            1. 固定文案里出现「顾客」——电商假设泄漏进通用 prompt，
               换成 IT 模板时语义就错了。已改为中性的「用户」，
               并补了一个用「中性 schema」断言固定文案无业务词的测试。
            2. 角色原本被套成「你是「你是XX」。」，重复。
               改为独立【角色】段，不再假设 role 的写法。
  模块 3  Extractor + JsonSchemaValidator + RetryPolicy  ✅ 完成
          extraction: Violation / ValidationResult / JsonOutputParser /
                      JsonSchemaValidator / RetryPolicy /
                      ExtractionResult / Extractor
          测试 25 个：JSON 解码 6 + 结构校验 12 + 总装与重试 6
                    + 真实失败路径 1

          已验证：
            解析容忍 `json 代码块与前后夹带解释
            校验覆盖 11 类违规（缺键/多余键/枚举越界/pattern/
            类型/日期格式/不存在的日期/image 非空/无依据/建议越界）
            「值为 null」被明确排除在违规之外，因此不会因信息缺失而重试
            重试带纠错提示（附上一次坏输出 + 违规清单），修好即停
            重试耗尽降级为全员缺失，不抛异常
            用不可满足的 pattern 打真实 API：attempts=1、
            模型输出 null、无违规 —— 说明规则 1 生效

          实测发现（已写回 idea.txt §9.7）：
            pattern 过严不会引发重试风暴，而是让模型改成输出 null，
            把真实可用的值静默吞掉，最终表现为「必填缺失 → 红级」。
            加 pattern 前必须核对真实数据格式。
  模块 4  MissingAnalyzer + QualityEvaluator（纯函数）   ✅ 完成
          quality: QualityLevel / MissingReport / MissingAnalyzer /
                   Defect / BasisCheck / FaithfulnessChecker /
                   ConsistencyChecker / QualityReport / QualityEvaluator
          测试 20 个：缺失判定 5 + 三维度与定级 12 + mock 契约 3

          已验证：
            与 mock/preview-response.json 逐项一致
              （level / 四个度量 / messages / missingRequired / missingOptional
               / faithfulness.coverage 全部相等）
            与 mock/ticket.json 的红级工单一致（原因句子 + 编排层追加的那一条）
            false 与 0 不判为缺失（否则会追问已经回答过的问题）
            未来日期只进 advisories，不改变级别（绿级仍为绿）
            结构违规复用抽取层的校验器做纵深防御，但剔除 BASIS_MISSING，
              避免与忠实性维度重复报同一件事

          实现时修正了 mock 自身的两处不自洽：
            102 号工单的 degrading 只有 1 条，实际应为 3 条（三个选填全缺）
            102 号工单既有 summary 文本、又列了「摘要为空」——已删后者
          现在的规则是：核验层产出前 N 条原因，
          编排层（模块 5）在「信息不全」时追加最后一条。
  模块 5  chat_session + Orchestrator + 追问闭环          ✅ 完成
          chat: SessionState / SessionView / ChatSession / ChatMessage /
                ChatSessionMapper / ChatMessageMapper /
                SessionStore(接口) / DbSessionStore /
                FollowUpBuilder / TicketDraft / OrchestratorResult /
                ConversationOrchestrator / ChatController / dto 5 个
          测试 9 个：追问 / 上限强制出单 / 直接出单 / 建议丢弃 /
                    出单后关闭 / 跨轮累积 / 入参校验 / 快照 / 降级不崩

          刻意做的一层抽象：SessionStore 是接口。
          编排逻辑因此可以脱离 MySQL 与 Spring 上下文被完整测试
          （测试用 InMemorySessionStore）。这也是「谁认识 MyBatis」的边界。

          已验证（真实 API 端到端）：
            场景 A 信息完整 → 一次出单，YELLOW，
                  messages = ["缺 1 项选填：商品问题照片"]
                  —— 与 mock/ticket.json 的 101 号工单完全一致
            场景 B 信息不足 → ASK → ASK → 强制出单 RED，
                  messages 含「追问已达上限，按「信息不全」出单，需人工补齐」
            场景 C 出单后再发消息 → 409「该会话已出单」

          实测发现并修正的问题：
            两次追问一字不差地重复（"为了帮您处理，还需要确认：订单号。"）。
            顾客会觉得系统没在听。已改为按轮次换措辞，
            第二次起用「还差一点信息就能处理了：请提供订单号。」
            —— 由 FollowUpBuilder 的 askedBefore 参数控制。

          追加：顾客确认（只对信息不全的工单）
            会话状态加 AWAITING_CONFIRM，chat_session 加 draft_summary，
            ticket 加 customer_confirmed
            追问超限 → 返回 CONFIRM（一句话摘要 + 还缺什么），不落库
            顾客确认（可改摘要）→ 才 publish，并标记 customerConfirmed
            待确认时顾客又补充 → 重新抽取，补齐则直出
            开关 app.chat.confirm-before-ticket（默认 true）
            测试从 9 个增到 15 个：待确认不落库 / 摘要兜底 / 改过摘要生效 /
            未确认状态拒绝 / 确认后关闭 / 补充后直出 / 开关关闭走旧行为
            验证：三轮缺订单号 → CONFIRM(RED, 缺订单号) → 确认 →
                 工单 #25、customerConfirmed=true、summary 采用顾客改过的那句
            顺手修掉一处边界泄漏：CONFIRM 响应原本带了 level=RED，
                 那是内部词汇，已移除（「信息不全」由 missing 列表表达）

          追加：工单状态回执
            ChatStatusService 读工单状态，命中变化才拼一句话写进会话。
            不调用模型：状态是确定性字段，让模型「检查」只会多一次说错的机会。
            chat_session.last_notified_status 保证同一状态只回执一次（幂等）。
            只回 status/decision/notice，不回 values/basis/qualityDetail。
            前端出单后每 4 秒轮询，到达 已解决/已关闭 停止；
            会话用 localStorage 续接，刷新后回执还能看到。
            测试从 123 增到 130：未出单不回执 / 初始状态静默 / 幂等 /
            状态前进再回执 / 无处理结果时不硬拼空内容 / 工单缺失报 404
            验证：PENDING 静默 → ASSIGNED 回执 → 重复轮询 changed=false →
                 RESOLVED 带回「处理结果：批准换货」，会话里留下两条回执

          追加：并发安全与人工端登录
            工单级乐观锁
              ticket 加 version + last_actor，@Version + OptimisticLockerInnerInterceptor
              PATCH 必须带 version：缺失→400，不匹配→409（提示里带两个版本号）
              updateById 影响 0 行 → 409（挡 check-then-act 的竞态）
              前端收到 409 自动 load() 刷新，再让人重新确认
            人工端登录
              agent 表（预置 张三/李四）+ BCrypt（只引 spring-security-crypto，
              不引 starter-security，否则全站被接管）
              cookie session，不引 JWT：单机没有多实例/跨域需求，
              而 JWT 的代价是不能真正注销
              AuthInterceptor 保护 /api/tickets/**、/api/templates/**
              assignee 兜底自动认领 + last_actor 记录（审计人来自会话，不由前端传）
            测试 130 → 143：版本缺失/不匹配/0 行冲突、自动认领、
              显式负责人优先、登录成功/密码错/账号不存在/停用/空输入、拦截器 401
            验证（真接口）：
              匿名 GET /api/tickets → 401
              错密码 → 401「用户名或密码不正确」（与账号不存在同一句）
              张三登录 → 1 号工单 v0→v1，assignee 自动=张三，lastActor=张三
              李四拿 v0 → 409「你看到的是 v0，当前是 v1」
              李四刷新后改 → 成功，v2，lastActor=李四，assignee 仍是张三
              退出后再访问 → 401

          追加：打回补全（人工 → 顾客 → 人工）
            状态机加 RETURNED：可从 PENDING / INFO_INCOMPLETE / ASSIGNED /
              PROCESSING 进入，只允许去向 PENDING（补全后）或 CLOSED
            ticket 加 revision / return_count / return_note
            打回封顶 2 次；打回后不允许直接标记为已解决
            对话层 ReturnFlow：懒重开会话（顾客来轮询或来发消息时才开），
              TICKETED → CHATTING，追问预算清零，追问只写一条
            TicketFactory.supplement()：原地更新同一张工单，
              内容整体替换、revision+1、状态回 PENDING、乐观锁保护
            这是对「工单是快照、不可编辑」原则的一次修订：
              可追溯改由 revision + last_actor + version 三件具体机制保证，
              且只允许在 RETURNED 状态下改动
            测试 143 → 158：状态流转（打回桥/非法目标）、封顶、重开只一次、
              追问优先用人工的话、缺字段按中文名列出、补全原地更新不新建
            验证（真接口 10/10）：
              红级 #55 → 打回(带说明) → 顾客轮询收到该说明且仅一条 →
              补齐两项 → 同一单号重新提交 resubmitted=true →
              状态 PENDING、revision=2、level 由 RED 变 YELLOW →
              收到「已重新提交」→ 第二次打回成功 → 第三次被拒
            顺手修掉一个真 bug：用户端首次进入时把回执丢了
              （resume() 拉历史在前、服务端重开在后，回执已被消费），
              表现为"卡片说请补充、但看不到要补什么"。

          （历史记录）过渡状态：OrchestratorResult.ticketId 曾恒为 null，
             因为工单落库属于模块 6。TicketDraft 已备齐全部材料
             （含 schema 快照与 QualityReport），模块 6 只需
             加一个 TicketPublisher 并在此处传入真实 id。
  模块 6  ticket + TicketFactory + 状态机                ✅ 完成
          ticket: Ticket / TicketMapper / TicketStatus /
                  TicketFactory / TicketService / TicketController /
                  dto 3 个
          测试 24 个：状态机 6 + 落库工厂 7 + 服务与流转 11

          已验证（真实 API 全链路）：
            一句完整对话 → 出单（ticketId=1，YELLOW）
            GET /api/tickets            列表带 level / status / needsClarify
            GET /api/tickets/{id}       详情含 values / basis /
                                        schemaSnapshot / qualityDetail
            PATCH 指派后 status=ASSIGNED assignee=张三
            PATCH 解决 → status=RESOLVED、decidedAt 写入、决策耗时非负
            非法流转被拦：RESOLVED → ASSIGNED 返回 400 并列出合法目标
            GET /api/tickets?level=YELLOW 过滤生效

          实测发现并修复的真 bug：
            决策耗时出现 -242ms。原因是 DATETIME 没有小数秒，
            MySQL 会把 created_at 的 .757 秒四舍五入到下一秒，
            于是「创建时间」比代码里的 now 还晚。
            两处一起修：
              1 schema.sql 所有时间列改为 DATETIME(3)，并已 ALTER 现有库
              2 decisionTimeMs 夹到 >= 0（数据库时间与 JVM 时间不保证同源）
            另加一条测试专门复现「created_at 晚于 now」这个场景。

          OrchestratorResult.ticketId 现在是真实值，
          ConversationOrchestrator 通过 TicketFactory 落库后再关闭会话
          （先落库再关会话：落库失败时顾客不至于白说一遍）。
  模块 7  前端 DynamicForm + 工单端                       ✅ 完成
          位置 web/（Vue 3 + Vite + Element Plus + Pinia + Axios，不用 TS）
          18 个文件：5 个页面 + 3 个组件 + 4 个 api 模块 + 路由/main/配置

          已验证：
            pnpm install 成功（131 个包）
            pnpm run build 成功（1697 模块，dist 已生成）
            vite dev 起在 5173，页面 200、SFC 能编译
            /api 代理到 8080 正常（经代理取到 2 个模板）

          两个复用点按设计落地：
            DynamicForm.vue  同时服务「模板试抽预览」与「工单详情」
            QualityBanner.vue 级别提示在列表与详情一致

          后续拆分（用户端 / 人工端）：
            前端改为多页：index.html（用户端）+ agent/index.html（人工端）
            共用代码收进 src/shared/，两端各自的 main/App/路由独立
            用户端新增 GET /api/chat/templates（只返回 id + 名称）
            两端验证通过：
              用户端 /            选类型 → 对话 → 出单，只显示工单号
              人工端 /agent/      工单列表看到同一张工单（#18 YELLOW）
              未知路径返回 404（原来会被兜底接成 500，已修）

          踩到的坑（已写进 web/README.md）：
            pnpm 11 默认拦截依赖安装脚本，且每次 run 前做依赖校验，
            校验不过就拒绝执行（ERR_PNPM_IGNORED_BUILDS）。
            解决：pnpm-workspace.yaml 里设 verifyDepsBeforeRun: false；
            或 pnpm approve-builds 交互批准一次；
            或直接用 node node_modules/vite/bin/vite.js 绕开。
            实测 esbuild 的二进制由平台包提供，脚本没跑也不影响构建。
  模块 8  preview 接口 + 模板编辑器                       ✅ 完成
          preview: PreviewRequest / PreviewResponse /
                   PreviewService / PreviewController
          测试 4 个（PreviewServiceTest）

          为什么单独开 preview 包，而不是塞进 template 控制器：
            template 是最底层，preview 要依赖 extraction 与 quality，
            放进去会形成向上依赖。路径仍挂在 /api/templates/{id}/preview，
            代码归属 preview 包。

          已验证（真实 API，经前端代理调用）：
            level=YELLOW，values/basis 与 mock/preview-response.json 完全一致
            metrics = 0.8 / 1.0 / 0.667 / 1.0
            messages = ["缺 1 项选填：商品问题照片"]
            信息不足时 level=RED 且 suggestion 被置空
            —— 试抽与真实出单表现一致，模板作者不会被误导

环境前提（已就绪）：
    MySQL 8.0.41 已启动，库 ai_ticket 已建，两张模板已入库
    DeepSeek API key 已验证可用（注意：请求 deepseek-chat
    实际返回 model=deepseek-flash，根因待确认）

复现命令：
    mysql -u root -p < src/main/resources/db/schema.sql
    mvn spring-boot:run
    curl http://localhost:8080/api/templates

================================================================
End of document
================================================================

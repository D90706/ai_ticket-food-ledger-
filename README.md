# AI 工单引擎 → 食材采购台账

> GitHub About：An LLM ticket-extraction engine (design prototype) and its production-shaped application to food-procurement ledgers. Spring Boot 3 + Vue 3 + DeepSeek.

本仓库包含两个独立可运行的工程，是**同一个方法论的两个阶段**：

| 目录 | 角色 | 端口 / 库 | 一句话 |
|---|---|---|---|
| `ai_ticket/` | **设计原型** | 8080 / `ai_ticket` | 把非结构化对话压成结构化、可决策、可分级的工单，`schema` 是数据不是代码 |
| `food-ledger/` | **业务落地** | 8081 / `food_ledger` | 同一个思路落到食材采购台账，并加上「合规」这条硬约束 |

原型解决的是**抽象层面的问题**：任意业务域如何把一段对话变成一个能决策的结构化对象。
落地解决的是**具体业务的问题**：食品安全法规定的必查项目必须齐、不合规的记录不许入账、每一笔都要可追溯。

两个工程**不共享代码**，只共享下面这三条设计判断。

---

## 一、共同的设计原则

整个项目的核心不是「用大模型」，而是**把模型的职责压到最小**：

```
    领域用户自定义字段与决策项
            │
            ▼
    ① 抽取   模型只回答「这段对话里看到了什么」
            │      values / basis / suggestion / summary
            ▼
    ② 核验   代码只回答「这些够不够用」——纯函数，无模型，可单测
            │      完备性 → 可决策性 / 忠实性 / 自洽性 → 分级
            ▼
    ③ 决策   人只回答「这件事怎么处理」
```

最重要的一处分界在 ① 和 ② 之间：

- **模型不输出置信度**，不给缺失清单，不参与定级——它没有这个能力
- **代码决定够不够**：缺 1 个必填项就是红级，这是比率计算，不是模型判断
- **人不为抽取质量背书**：分级只说明「信息够不够」，不说明「结论对不对」

实测依据：缺 1 个必填项时模型给出的 confidence 是 0.75，还自信地附上了处理建议。
所以两个工程里都没有「让模型给自己打分」这一层。

---

## 二、`ai_ticket` —— 设计原型

**Schema 驱动的对话式工单生成引擎。** 换业务只改配置，不改代码。

### 四层架构

| 层 | 内容 |
|---|---|
| 模板层 | `role` / `knowledge` / `decision_options` / `fields`（含必填与选填），模板驱动 prompt，版本递增 |
| 抽取层 | 动态 prompt → LLM → `values` / `basis` / `suggestion` / `summary`，含校验重试与输出解码 |
| 核验层 | 缺失判定、忠实性（依据子串校验）、自洽性、分级——全部纯函数 |
| 工单层 | 落库、状态机、指派、决策记录、schema 快照 |

### 技术栈

- 后端 Spring Boot **3.4.8** / Java 23 / MyBatis-Plus / MySQL 8 / springdoc（`/swagger-ui.html`）
- 前端 Vue 3 + Vite + Element Plus（`web/`，用户端 `/`、人工端 `/agent/`）
- 模型 DeepSeek（OpenAI 兼容协议，裸调 HTTP，可替换点隔离在 `llm/` 接口后）

### 几个刻意的机制

- **打回补全**：人工处理不了可以把工单打回（`RETURNED`），会话重开，顾客补齐后 AI 重新抽取并**原地重提交**（`revision + 1`）。最多打回 2 次，防止死循环
- **工单级乐观锁**：带 `version` 保存，版本不符返回 409 并提示「你看到的是 v0，当前是 v1」；不传 `version` 直接 400
- **顾客确认**：只有红级工单才让顾客确认一句话摘要（可编辑），拿「语义正确性」信号；绿黄级不打扰
- **只做登录、不做权限**：人工端预置账号 + BCrypt + session；顾客端匿名。这是明确的安全边界，不是遗漏

### 测试与文档

- **116 个用例**：108 纯单元（不碰网络不碰库）+ 4 真实 API 冒烟 + 4 持久化集成，缺依赖时自动跳过
- `idea.txt`（60 KB，设计思路）· `design-modules.md`（47 KB，模块划分与八模块进度）· `tech-stack.md` · `mock/`（前后端契约）· `spike/`（模拟基线与真实 API 实验）

---

## 三、`food-ledger` —— 业务落地

**食材采购台账系统。** 录入、查询、筛选、统计、导出、批次追溯、临期预警，加上 AI 录入与合规判定。

### 核心红线

> **AI 只生成「待入账」记录，绝不直接入账。**

台账是给监管看的东西，一条模型幻觉进去就是合规事故。所以「入账」这一步必须由人按一次键。
这条红线是小模型能力的边界，也是这个业务能成立的边界。

### 比原型多出来的东西

| 能力 | 说明 |
|---|---|
| **合规判定** | 按《食品安全法》第五十三条把法定查验项目写死，缺项即不合规，不合规不许入账 |
| **字段设计** | 一半是列、一半是 JSON——法定项目固定成列便于查询统计，各行业自定义项放 JSON |
| 退回补录 | 管理员可把不合格记录退回给录入人补充，留痕 |
| 台账运营 | 批次追溯、临期预警、统计看板、Excel 导出（POI） |

### 技术栈

- 后端 Spring Boot **3.4.8** / Java 23 / MyBatis-Plus / MySQL 8 / POI（导出）/ springdoc
- 前端 Vue 3 + Vite（`web/`，采购端 + 管理端）
- 模型 DeepSeek（`deepseek-chat`），确认开关 `app.chat.confirm-before-record`

### 测试与交付物

- **133 条用例（P0 57 条）**，覆盖 15 个模块：`docs/test-plan.md`、`docs/测试文档.md`(+`.docx`)、`docs/测试用例-手工执行版.xlsx`、`docs/eval-report.md`
- **15 个自动化脚本**（`qa/`）：测试执行器、越权/注入探测、证照上传校验、对话样本校验、安装校验、UI 点击测试、截图采集、PPT 生成
- 答辩材料：`docs/答辩PPT-食材采购台账系统.pptx`、`答辩讲稿.md`（25 KB）、`答辩分工.md`、`升级方案.md`、`模块划分.md`、`docs/screenshots/`

---

## 四、目录结构

```
ai_ticket(food-ledger)/
├── ai_ticket/                    # 设计原型（8080）
│   ├── src/main/java/com/example/ticket/
│   │   ├── template/             # schema 存取、校验、版本递增
│   │   ├── extraction/           # PromptBuilder / Extractor / 重试 / 输出解码
│   │   ├── quality/              # 缺失判定 / 忠实性 / 自洽性 / 分级
│   │   ├── chat/                 # 会话、追问闭环、打回补录
│   │   ├── ticket/               # 落库、状态机、决策、乐观锁
│   │   ├── llm/ retrieval/       # 可替换点（模型、检索）
│   │   └── preview/ auth/ common/
│   ├── src/main/resources/db/schema.sql   # 建表 + 2 个模板种子
│   ├── web/                      # Vue 3 用户端 + 人工端
│   ├── mock/ spike/ qa/          # 契约、实验数据、边界用例
│   └── idea.txt design-modules.md tech-stack.md README.md
└── food-ledger/                  # 业务落地（8081）
    ├── src/main/java/com/example/ledger/
    │   ├── entry/                # 一句话录入 → 抽取 → 追问
    │   ├── record/               # 台账、合规判定、退回、追溯
    │   ├── template/             # 台账模板（法定项目 + 自定义项）
    │   ├── supplier/ category/ staff/ stats/ export/ upload/
    │   └── common/ config/ llm/
    ├── src/main/resources/db/    # schema.sql + data.sql
    ├── web/                      # Vue 3 采购端 + 管理端
    ├── qa/                       # 15 个验证脚本
    └── docs/                     # 测试计划、用例、答辩材料
```

---

## 五、怎么跑

两个工程端口刻意错开，避免「起了哪个都不知道」。各自目录内执行：

```powershell
# 原型：ai_ticket（8080 / 前端 5173）
cd ai_ticket
mysql -u root -p < src/main/resources/db/schema.sql
mvn spring-boot:run
cd web; npm install; npm run dev

# 落地：food-ledger（8081 / 前端 5174）
cd food-ledger
mysql -u root -p < src/main/resources/db/schema.sql
mvn spring-boot:run
cd web; npm install; npm run dev
```

前提：JDK 23+、Maven 3.9+、MySQL 8、Node 18+，以及一个可用的 DeepSeek key
（只通过环境变量 `DEEPSEEK_API_KEY` 注入；仓库里不含任何 key，
未配置时启动不受影响，只有调用模型的功能会报错）。
数据库口令也必须用 `DB_PASSWORD` 环境变量提供——两个 `application.yml` 里都只剩
`${DB_PASSWORD:}` 占位符，留空会连不上库（这是刻意的，避免默认口令被带进仓库）。

---

## 六、明确不做的事

这些是刻意的边界，不是未完成项：

| 不做 | 原因 |
|---|---|
| 让模型自评置信度 | 实测不可靠；没有标注数据时任何「0.87」都是修辞 |
| 图片上传（原型） | 只渲染「需人工补充」，不做上传接口 |
| 权限分级、数据隔离 | 只做「登录 / 没登录」两档；做一半的归属校验比明说没有更危险 |
| 提交前就生成工单 | 信息不全的工单不直接入队，先让顾客/录入人确认 |
| 流式输出 | 端到端 0.6–1.3 秒，输出是结构化 JSON，流式没有可服务的东西 |

---

## 七、文档索引

**原型**：`ai_ticket/idea.txt`（为什么这样设计）· `design-modules.md`（模块职责与「不做什么」）· `tech-stack.md`（技术选型）· `spike/spike-02-real.md`（真实 API 实验与六个发现）

**落地**：`food-ledger/docs/test-plan.md`、`测试文档.md`、`测试数据.md`、`eval-report.md`、`模块划分.md`、`升级方案.md`、`答辩讲稿.md`

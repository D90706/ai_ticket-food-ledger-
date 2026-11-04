-- =============================================================
-- AI 客服工单系统 · 建表脚本
-- MySQL 8，utf8mb4
--
-- ⚠️ 本脚本会 DROP 四张表再重建：重跑等于清空所有数据。
--    只想补初始模板的话，别重跑整个文件，单独执行末尾的 INSERT。
--
-- 说明：一次建好四张表。四个模块各用一张，先建好可以避免后面再迁移。
--       末尾插入两个模板：电商售后（主）与 IT 报修（用于验证通用性）。
-- =============================================================

CREATE DATABASE IF NOT EXISTS ai_ticket
    DEFAULT CHARACTER SET utf8mb4
    COLLATE utf8mb4_general_ci;

USE ai_ticket;

-- -------------------------------------------------------------
-- 模板：一个业务领域的完整配置包
-- JSON 列统一用字符串映射，反序列化集中在 TemplateService，
-- 不使用 MyBatis-Plus 的 TypeHandler（避免 autoResultMap 那类坑）。
-- -------------------------------------------------------------
DROP TABLE IF EXISTS template;
CREATE TABLE template (
    id               BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    name             VARCHAR(100) NOT NULL COMMENT '模板名',
    version          INT          NOT NULL DEFAULT 1 COMMENT '版本号，每次改字段 +1',
    role_text        VARCHAR(500) NOT NULL COMMENT 'AI 扮演的角色',
    knowledge        MEDIUMTEXT   NULL COMMENT '领域知识，全量注入 prompt',
    decision_options JSON         NOT NULL COMMENT '可用决策项（字符串数组）',
    fields           JSON         NOT NULL COMMENT '字段定义（数组）',
    enabled          TINYINT      NOT NULL DEFAULT 1 COMMENT '是否启用',
    created_at       DATETIME(3)     NOT NULL COMMENT '创建时间',
    updated_at       DATETIME(3)     NOT NULL COMMENT '更新时间',
    PRIMARY KEY (id),
    KEY idx_enabled (enabled)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '领域配置包';

-- -------------------------------------------------------------
-- 会话：跨轮次累积抽取结果
-- collected_values 必须累积，因为顾客补充订单号时，
-- 模型未必会把上一轮的原因一起复述出来。
-- -------------------------------------------------------------
DROP TABLE IF EXISTS chat_session;
CREATE TABLE chat_session (
    id               BIGINT      NOT NULL AUTO_INCREMENT,
    template_id      BIGINT      NOT NULL,
    template_version INT         NOT NULL,
    state            VARCHAR(20) NOT NULL COMMENT 'CHATTING / TICKETED',
    followup_count   INT         NOT NULL DEFAULT 0 COMMENT '已追问次数',
    collected_values JSON        NULL,
    collected_basis  JSON        NULL,
    draft_summary    VARCHAR(300) NULL COMMENT '待确认时的一句话摘要（顾客可改）',
    last_notified_status VARCHAR(20) NULL COMMENT '已回执过的工单状态',
    ticket_id        BIGINT      NULL,
    created_at       DATETIME(3)    NOT NULL,
    updated_at       DATETIME(3)    NOT NULL,
    PRIMARY KEY (id),
    KEY idx_template (template_id),
    KEY idx_state (state)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '对话会话';

-- -------------------------------------------------------------
-- 消息
-- -------------------------------------------------------------
DROP TABLE IF EXISTS chat_message;
CREATE TABLE chat_message (
    id         BIGINT      NOT NULL AUTO_INCREMENT,
    session_id BIGINT      NOT NULL,
    role       VARCHAR(20) NOT NULL COMMENT 'user / assistant / system',
    content    TEXT        NOT NULL,
    created_at DATETIME(3)    NOT NULL,
    PRIMARY KEY (id),
    KEY idx_session (session_id, id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '对话消息';

-- -------------------------------------------------------------
-- 工单：三个必须记住的字段
--   schema_snapshot  渲染唯一依据，永不回查当前模板
--   quality_detail   存三维度明细，不只是结论
--   needs_clarify    人工点「需联系顾客」，即回问率埋点
-- -------------------------------------------------------------
DROP TABLE IF EXISTS ticket;
CREATE TABLE ticket (
    id               BIGINT       NOT NULL AUTO_INCREMENT,
    template_id      BIGINT       NOT NULL,
    template_version INT          NOT NULL,
    schema_snapshot  JSON         NOT NULL COMMENT '生成那一刻的 schema 快照',
    values_data      JSON         NOT NULL COMMENT '抽取出的字段值',
    basis            JSON         NULL COMMENT '枚举值的原文依据',
    summary          TEXT         NULL COMMENT '一句话概述，不参与决策',
    suggestion       VARCHAR(100) NULL COMMENT 'AI 建议决策，缺失必填时强制置空',
    quality_level    VARCHAR(10)  NOT NULL COMMENT 'RED / YELLOW / GREEN',
    quality_detail   JSON         NOT NULL COMMENT '三维度明细 + 比率 + messages',
    status           VARCHAR(20)  NOT NULL COMMENT 'PENDING/ASSIGNED/PROCESSING/RESOLVED/CLOSED/INFO_INCOMPLETE',
    assignee         VARCHAR(50)  NULL,
    decision         VARCHAR(100) NULL COMMENT '人工最终决定',
    session_id         BIGINT       NULL,
    customer_confirmed TINYINT      NOT NULL DEFAULT 0 COMMENT '顾客是否在入队前确认过内容',
    version            INT          NOT NULL DEFAULT 0 COMMENT '乐观锁版本，防并发覆盖',
    last_actor         VARCHAR(50)  NULL COMMENT '最后一次修改者（登录客服）',
    revision           INT          NOT NULL DEFAULT 1 COMMENT '内容修订号，打回补全后 +1',
    return_count       INT          NOT NULL DEFAULT 0 COMMENT '被打回次数，用于封顶',
    return_note        VARCHAR(200) NULL COMMENT '人工打回时给顾客的话',
    needs_clarify      TINYINT      NOT NULL DEFAULT 0 COMMENT '人工是否还需回问顾客',
    decision_time_ms INT          NULL COMMENT '决策耗时',
    created_at       DATETIME(3)     NOT NULL,
    decided_at       DATETIME(3)     NULL,
    PRIMARY KEY (id),
    KEY idx_status (status, created_at),
    KEY idx_template (template_id, created_at),
    KEY idx_level (quality_level)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '决策工单';

-- -------------------------------------------------------------
-- 初始数据：与 mock/template.json 保持一致
-- -------------------------------------------------------------
INSERT INTO template
    (name, version, role_text, knowledge, decision_options, fields, enabled, created_at, updated_at)
VALUES (
    '电商售后工单',
    3,
    '你是服装店的售后助手，只负责把顾客的诉求整理成工单。语气克制，不承诺赔付、不承诺到货时间。',
    '【退换货政策】签收 7 天内可无理由退换，商品需不影响二次销售。吊牌已剪、已洗涤、已穿着外出的不支持无理由退换。质量问题由卖家承担往返运费；非质量问题由买家承担。超过 7 天的，只有质量问题可以申请售后。【尺码表】XS 适合 150-155 / S 适合 155-165 / M 适合 165-170 / L 适合 170-178 / XL 适合 178-185',
    '["批准换货","批准退货","拒绝","要求补证","转售后专岗"]',
    '[
      {"key":"order_id","label":"订单号","type":"text","required":true,"options":null,"pattern":"^\\\\d{8,20}$"},
      {"key":"reason","label":"原因","type":"enum","required":true,"options":["尺码不合适","质量问题","发错货","其他"],"pattern":null},
      {"key":"worn","label":"是否已穿着或洗涤","type":"boolean","required":false,"options":null,"pattern":null},
      {"key":"size_wanted","label":"希望换成的尺码","type":"text","required":false,"options":null,"pattern":"^(XS|S|M|L|XL|XXL)$"},
      {"key":"evidence","label":"商品问题照片","type":"image","required":false,"options":null,"pattern":null}
    ]',
    1,
    NOW(),
    NOW()
);

-- -------------------------------------------------------------
-- 第二个模板：IT 设备报修工单
--
-- 它存在的意义不是「多支持一个业务」，而是用于验证通用性：
-- 与电商模板的结构差异很大（设备中心、有 date 字段、
-- 必填项在部分场景天然拿不到），
-- 而抽取层用的是同一套 PromptBuilder、同一套校验、同一套核验。
--
-- 字段里刻意保留了那个实测发现的坑：
--   device_no 是必填，但「会议室 Wi-Fi 全断」根本没有设备编号。
--   → 追问两次仍缺 → 强制出单并标「信息不全」，这是唯一出口。
-- -------------------------------------------------------------
INSERT INTO template
    (name, version, role_text, knowledge, decision_options, fields, enabled, created_at, updated_at)
VALUES (
    'IT 设备报修工单',
    1,
    '你是公司 IT 服务台的接单助手，只做信息整理，不判断责任、不承诺修复时间。',
    '【处理流程】硬件故障转技术支持；账号权限问题转信息科；网络问题先远程排查，再决定是否上门。影响面为「全公司」的事件需 30 分钟内响应，夜间由值班工程师处理。【设备编号规则】办公电脑形如 PC-2041，打印设备形如 PR-0071，会议室无固定设备编号。',
    '["指派技术支持","远程处理","上门维修","更换设备","拒绝"]',
    '[
      {"key":"device_no","label":"设备编号","type":"text","required":true,"options":null,"pattern":"^(PC|PR)-\\\\d{4}$"},
      {"key":"fault_type","label":"故障类型","type":"enum","required":true,"options":["硬件故障","软件故障","网络问题","账号权限","其他"],"pattern":null},
      {"key":"impact_scope","label":"影响范围","type":"enum","required":true,"options":["仅本人","整个部门","全公司"],"pattern":null},
      {"key":"urgent","label":"是否影响当前正在进行的业务","type":"boolean","required":false,"options":null,"pattern":null},
      {"key":"first_seen","label":"问题最早出现日期","type":"date","required":false,"options":null,"pattern":null},
      {"key":"screenshot","label":"报错截图","type":"image","required":false,"options":null,"pattern":null}
    ]',
    1,
    NOW(),
    NOW()
);
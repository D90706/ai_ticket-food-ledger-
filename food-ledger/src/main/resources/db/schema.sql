-- ============================================================
--  食材采购台账系统 · 表结构
-- ------------------------------------------------------------
--  设计说明（重要，别只看 DDL）：
--
--  1. 法定项目是固定列，不是 JSON。
--     《食品安全法》第五十三条把进货查验记录的项目写死了：
--     名称、规格、数量、生产日期/批号、保质期、进货日期、
--     供货者名称、地址、联系方式。
--     法律固定 = schema 固定。给它们建列，才谈得上
--     「按日期筛选、按供应商汇总、按批号追溯、导出 Excel」。
--
--  2. 单位自定义字段放 extra_json。
--     产地、冷链温度、农残检测报告号……这些因人而异，
--     由台账模板声明，前端按模板渲染，SQL 不关心。
--
--  3. 质量分级与溯源字段（quality_level / basis / source_*）
--     是「我们的想法」落在这张表上的痕迹：
--     每条记录都能回答「谁录的、AI 从哪句话抽的、缺什么」。
--
--  ⚠ 开发期重建：先 DROP 再 CREATE。
--     生产环境请用迁移脚本，别用这个文件。
-- ============================================================

CREATE DATABASE IF NOT EXISTS food_ledger
    DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;

USE food_ledger;

DROP TABLE IF EXISTS entry_message;
DROP TABLE IF EXISTS entry_session;
DROP TABLE IF EXISTS ledger_record;
DROP TABLE IF EXISTS ledger_template;
DROP TABLE IF EXISTS ingredient_category;
DROP TABLE IF EXISTS supplier;
DROP TABLE IF EXISTS staff;

-- ------------------------------------------------------------
--  1. staff 员工账号
--     采购员录入，管理员审核入账。密码只存 BCrypt。
-- ------------------------------------------------------------
CREATE TABLE staff (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    username      VARCHAR(50)  NOT NULL,
    display_name  VARCHAR(50)  NOT NULL,
    password_hash VARCHAR(100) NOT NULL,
    role          VARCHAR(20)  NOT NULL COMMENT 'PURCHASER 采购员 / MANAGER 管理员',
    enabled       TINYINT      NOT NULL DEFAULT 1,
    created_at    DATETIME(3)  NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_staff_username (username)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '员工账号';

-- ------------------------------------------------------------
--  2. supplier 供应商
--     台账合规的一半在供应商：证照过期，这笔采购就不合规。
-- ------------------------------------------------------------
CREATE TABLE supplier (
    id                  BIGINT       NOT NULL AUTO_INCREMENT,
    name                VARCHAR(120) NOT NULL COMMENT '供货者名称',
    address             VARCHAR(200) NULL,
    contact             VARCHAR(50)  NULL COMMENT '联系人',
    phone               VARCHAR(50)  NULL,
    license_no          VARCHAR(80)  NULL COMMENT '食品经营许可证号',
    license_expiry      DATE         NULL COMMENT '许可证有效期至',
    business_license_no VARCHAR(80)  NULL COMMENT '营业执照号',
    business_expiry     DATE         NULL,
    status              VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE / DISABLED',
    remark              VARCHAR(200) NULL,
    created_at          DATETIME(3)  NOT NULL,
    updated_at          DATETIME(3)  NOT NULL,
    PRIMARY KEY (id),
    KEY idx_supplier_name (name),
    KEY idx_supplier_license_expiry (license_expiry)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '供应商';

-- ------------------------------------------------------------
--  3. ingredient_category 食材品类
--     只做分类统计与筛选，不做复杂树。食堂场景不需要无限层级。
-- ------------------------------------------------------------
CREATE TABLE ingredient_category (
    id         BIGINT      NOT NULL AUTO_INCREMENT,
    name       VARCHAR(50) NOT NULL,
    sort_no    INT         NOT NULL DEFAULT 0,
    created_at DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_category_name (name)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '食材品类';

-- ------------------------------------------------------------
--  4. ledger_template 台账模板
--     「字段可自定义」就落在这里：法定项目之外，单位自己加。
--     fields 里每个字段带 fixed=true 表示法定项目（不可删）。
-- ------------------------------------------------------------
CREATE TABLE ledger_template (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    name            VARCHAR(100) NOT NULL,
    version         INT          NOT NULL DEFAULT 1,
    fields          JSON         NOT NULL COMMENT '自定义字段定义（法定项目标记 fixed）',
    shelf_life_warn_days INT     NOT NULL DEFAULT 3 COMMENT '临期预警天数',
    enabled         TINYINT      NOT NULL DEFAULT 1,
    created_at      DATETIME(3)  NOT NULL,
    updated_at      DATETIME(3)  NOT NULL,
    PRIMARY KEY (id),
    -- 模板名唯一：配合种子文件里的 INSERT IGNORE，把「重跑种子」变成幂等的。
    -- 否则会插出两份同名模板，默认模板取哪份就成了看 id 的玄学。
    UNIQUE KEY uk_template_name (name)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '台账模板';

-- ------------------------------------------------------------
--  5. ledger_record 台账记录（一行 = 一个品项）
-- ------------------------------------------------------------
CREATE TABLE ledger_record (
    id                BIGINT       NOT NULL AUTO_INCREMENT,

    -- ---- 法定项目（固定列）----
    ingredient_name   VARCHAR(100) NOT NULL COMMENT '名称',
    spec              VARCHAR(100) NULL     COMMENT '规格',
    quantity          DECIMAL(12,3) NULL    COMMENT '数量',
    unit              VARCHAR(20)  NULL     COMMENT '单位',
    production_date   DATE         NULL     COMMENT '生产日期',
    batch_no          VARCHAR(60)  NULL     COMMENT '生产批号',
    shelf_life        VARCHAR(50)  NULL     COMMENT '保质期（如 12个月）',
    purchase_date     DATE         NULL     COMMENT '进货日期',
    supplier_name     VARCHAR(120) NULL     COMMENT '供货者名称',
    supplier_address  VARCHAR(200) NULL     COMMENT '地址',
    supplier_phone    VARCHAR(50)  NULL     COMMENT '联系方式',

    -- ---- 索证索票 ----
    certificate       VARCHAR(200) NULL COMMENT '凭证摘要：许可证号/合格证号',
    certificate_ok    TINYINT      NOT NULL DEFAULT 0 COMMENT '索证索票是否齐全',
    attachments       JSON         NULL COMMENT '附件（索证索票照片、检验报告扫描件）',

    -- ---- 需要统计与预警的固定列 ----
    expiry_date       DATE         NULL COMMENT '到期日（由生产日期+保质期推算或录入）',
    category_id       BIGINT       NULL,
    supplier_id       BIGINT       NULL COMMENT '来自供应商主数据时可空',
    unit_price        DECIMAL(12,2) NULL,
    amount            DECIMAL(14,2) NULL COMMENT '金额 = 数量 × 单价',
    warehouse         VARCHAR(60)  NULL COMMENT '入库食堂/仓库',
    receiver          VARCHAR(50)  NULL COMMENT '验收人',
    remark            VARCHAR(500) NULL,

    -- ---- 自定义字段 ----
    extra_json        JSON         NULL COMMENT '模板声明的自定义字段值',

    -- ---- 模板快照（渲染依据，永不回查当前模板）----
    template_id       BIGINT       NOT NULL,
    template_version  INT          NOT NULL,
    schema_snapshot   JSON         NOT NULL,

    -- ---- 我们的想法：质量分级与溯源 ----
    quality_level     VARCHAR(10)  NOT NULL COMMENT 'RED 不合规 / YELLOW 待补证 / GREEN 完整',
    quality_detail    JSON         NOT NULL COMMENT '三维度明细，不只是结论',
    basis             JSON         NULL COMMENT '字段的原文依据（AI 录入时有值）',
    summary           VARCHAR(300) NULL COMMENT '一句话说明（AI 录入时有值）',
    status            VARCHAR(20)  NOT NULL DEFAULT 'PENDING'
                      COMMENT 'PENDING 待入账 / POSTED 已入账 / RETURNED 退回补录 / VOID 已作废',

    -- ---- 录入方式 ----
    source_type       VARCHAR(20)  NOT NULL DEFAULT 'MANUAL' COMMENT 'MANUAL 手填 / AI 对话抽取',
    source_session_id BIGINT       NULL COMMENT 'AI 录入时对应的会话',

    -- ---- 修订与打回 ----
    revision          INT          NOT NULL DEFAULT 1 COMMENT '内容修订号',
    return_count      INT          NOT NULL DEFAULT 0,
    return_note       VARCHAR(200) NULL,

    -- ---- 审计与并发 ----
    version           INT          NOT NULL DEFAULT 0 COMMENT '乐观锁',
    created_by        VARCHAR(50)  NOT NULL,
    last_actor        VARCHAR(50)  NULL,
    posted_by         VARCHAR(50)  NULL,
    posted_at         DATETIME(3)  NULL,
    void_reason       VARCHAR(200) NULL,
    created_at        DATETIME(3)  NOT NULL,
    updated_at        DATETIME(3)  NOT NULL,

    PRIMARY KEY (id),
    KEY idx_record_purchase_date (purchase_date),
    KEY idx_record_supplier (supplier_id),
    KEY idx_record_supplier_name (supplier_name),
    KEY idx_record_category (category_id),
    KEY idx_record_status (status),
    KEY idx_record_level (quality_level),
    KEY idx_record_expiry (expiry_date),
    KEY idx_record_batch (batch_no)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '采购台账记录';

-- ------------------------------------------------------------
--  6. entry_session / entry_message AI 录入会话
--     采购员用一句话描述一笔采购，AI 抽成台账草稿。
-- ------------------------------------------------------------
CREATE TABLE entry_session (
    id                 BIGINT      NOT NULL AUTO_INCREMENT,
    template_id        BIGINT      NOT NULL,
    template_version   INT         NOT NULL,
    state              VARCHAR(20) NOT NULL DEFAULT 'CHATTING'
                       COMMENT 'CHATTING / AWAITING_CONFIRM / RECORDED',
    followup_count     INT         NOT NULL DEFAULT 0,
    collected_values   JSON        NULL,
    collected_basis    JSON        NULL,
    draft_summary      VARCHAR(300) NULL,
    last_notified_status VARCHAR(20) NULL,
    record_id          BIGINT      NULL,
    created_by         VARCHAR(50) NOT NULL,
    created_at         DATETIME(3) NOT NULL,
    updated_at         DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    KEY idx_session_state (state)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = 'AI 录入会话';

CREATE TABLE entry_message (
    id         BIGINT      NOT NULL AUTO_INCREMENT,
    session_id BIGINT      NOT NULL,
    role       VARCHAR(20) NOT NULL,
    content    TEXT        NOT NULL,
    created_at DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    KEY idx_message_session (session_id, id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = 'AI 录入会话消息';

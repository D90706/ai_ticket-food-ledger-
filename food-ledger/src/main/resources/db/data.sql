-- ============================================================
--  种子数据
-- ------------------------------------------------------------
--  员工账号不在这里插入：密码必须是 BCrypt 哈希，
--  而在 SQL 里写死一串 $2a$10$... 是不可解释的魔法值。
--  账号由 StaffSeeder 在启动时补齐（见 Java）。
-- ============================================================

-- 目标库由命令行指定，不在这里写 USE：
--   mysql -u root food_ledger < db/data.sql
-- 写死 USE 的话，一旦有人想往别的库铺种子（测试库、演示库），
-- 语句会悄悄打回生产库——这种错误不会报错，只会写错地方。

-- ------------------------------------------------------------
--  食材品类（食堂常见分类，够用即可，不做无限层级）
--  INSERT IGNORE：重跑这份种子不会因唯一键报错
-- ------------------------------------------------------------
INSERT IGNORE INTO ingredient_category (name, sort_no, created_at) VALUES
    ('蔬菜', 10, NOW(3)),
    ('肉类', 20, NOW(3)),
    ('水产', 30, NOW(3)),
    ('粮油', 40, NOW(3)),
    ('蛋奶', 50, NOW(3)),
    ('调味品', 60, NOW(3)),
    ('冷冻食品', 70, NOW(3)),
    ('干货', 80, NOW(3));

-- ------------------------------------------------------------
--  默认台账模板
-- ------------------------------------------------------------
--  fields 的两个关键约定：
--    fixed = true  法定项目，不可删除（食品安全法第 53 条）
--    fixed = false 单位自定义，可由管理员增删
--  前端就靠这个 flag 决定哪些字段能删。
-- INSERT IGNORE + 唯一键 uk_template_name：重跑这份种子不会插出第二份模板
INSERT IGNORE INTO ledger_template (name, version, fields, shelf_life_warn_days, enabled, created_at, updated_at)
VALUES (
    '食材采购台账',
    1,
    '[
      {"key":"ingredient_name","label":"名称","type":"text","required":true,"fixed":true,
       "hint":"食材通用名称，如「土豆」「冷鲜猪肉」"},
      {"key":"spec","label":"规格","type":"text","required":true,"fixed":true,
       "hint":"如「散装」「500g/袋」「25kg/箱」"},
      {"key":"quantity","label":"数量","type":"number","required":true,"fixed":true},
      {"key":"unit","label":"单位","type":"enum","required":true,"fixed":true,
       "options":["斤","公斤","箱","袋","瓶","桶","包"]},
      {"key":"production_date","label":"生产日期","type":"date","required":true,"fixed":true},
      {"key":"batch_no","label":"生产批号","type":"text","required":true,"fixed":true,
       "hint":"包装上的批号；没有批号的散装食材填生产日期"},
      {"key":"shelf_life","label":"保质期","type":"text","required":true,"fixed":true,
       "hint":"如「12个月」「7天」「冷藏3天」"},
      {"key":"purchase_date","label":"进货日期","type":"date","required":true,"fixed":true},
      {"key":"supplier_name","label":"供货者名称","type":"text","required":true,"fixed":true},
      {"key":"supplier_address","label":"供货者地址","type":"text","required":true,"fixed":true},
      {"key":"supplier_phone","label":"联系方式","type":"text","required":true,"fixed":true},
      {"key":"certificate","label":"索证索票凭证","type":"text","required":true,"fixed":true,
       "hint":"许可证号 / 检验合格证号 / 检疫证明编号"},

      {"key":"expiry_date","label":"到期日","type":"date","required":false,"fixed":false,
       "hint":"按生产日期+保质期推算；填了才能做临期预警"},

      {"key":"category","label":"品类","type":"enum","required":false,"fixed":false,
       "hint":"按品名判断归属，如鸡肉属于肉类、带鱼属于水产；选不准就留空",
       "options":["蔬菜","肉类","水产","粮油","蛋奶","调味品","冷冻食品","干货"]},

      {"key":"unit_price","label":"单价","type":"number","required":false},
      {"key":"amount","label":"金额","type":"number","required":false},
      {"key":"warehouse","label":"入库仓库","type":"text","required":false},
      {"key":"receiver","label":"验收人","type":"text","required":false},
      {"key":"origin","label":"产地","type":"text","required":false},
      {"key":"cold_chain_temp","label":"冷链到货温度(℃)","type":"text","required":false,
       "hint":"冷冻/冷藏食材填写，如「-18」「4」"},
      {"key":"pesticide_report","label":"农残检测报告号","type":"text","required":false}
     ]',
    3,
    1,
    NOW(3),
    NOW(3)
);

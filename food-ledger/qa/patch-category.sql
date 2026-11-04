-- 给已在运行的库补上「品类」字段（模板 v4）
--
-- 背景：ledger_record.category_id 一直存在、列表也能按它筛，
-- 但模板字段清单里没有 category —— 于是录入时永远填不进去，
-- 23 条记录里只有 1 条有品类，按品类筛自然「漏掉鸡肉」。
-- 这和之前的 expiry_date、attachments 是同一类缺陷：
-- **有列、能筛、但录不进去**。
--
-- 用 JSON_ARRAY_APPEND 精确追加，不要用 REPLACE 改字符串：
-- 字段定义里有 options 数组，字符串替换会误伤。

USE food_ledger;

UPDATE ledger_template
   SET fields = JSON_ARRAY_APPEND(fields, '$', JSON_OBJECT(
         'key', 'category',
         'label', '品类',
         'type', 'enum',
         'required', false,
         'fixed', false,
         'hint', '按品名判断归属，如鸡肉属于肉类、带鱼属于水产；选不准就留空',
         'options', JSON_ARRAY('蔬菜','肉类','水产','粮油','蛋奶','调味品','冷冻食品','干货')
       )),
       version = version + 1,
       updated_at = NOW(3)
 WHERE id = 1
   AND JSON_SEARCH(fields, 'one', 'category') IS NULL;

SELECT id, version, JSON_LENGTH(fields) AS field_count,
       JSON_SEARCH(fields, 'one', 'category') AS category_found
  FROM ledger_template;

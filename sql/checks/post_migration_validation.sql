-- V1 至 V7 完成后的只读检查。正常空库中，各异常明细查询应返回 0 行。

-- 1. 应有 39 张平台业务表，missing_table 为 NULL 表示无缺表。
WITH expected_tables AS (
  SELECT 'stock_block_rt_info' AS table_name UNION ALL
  SELECT 'stock_business' UNION ALL
  SELECT 'stock_market_index_info' UNION ALL
  SELECT 'stock_outer_market_index_info' UNION ALL
  SELECT 'stock_rt_info' UNION ALL
  SELECT 'sys_log' UNION ALL
  SELECT 'sys_permission' UNION ALL
  SELECT 'sys_role' UNION ALL
  SELECT 'sys_role_permission' UNION ALL
  SELECT 'sys_user' UNION ALL
  SELECT 'sys_user_role' UNION ALL
  SELECT 'external_provider' UNION ALL
  SELECT 'data_sync_checkpoint' UNION ALL
  SELECT 'stock_exchange' UNION ALL
  SELECT 'stock_security' UNION ALL
  SELECT 'stock_security_status_history' UNION ALL
  SELECT 'stock_sector' UNION ALL
  SELECT 'stock_security_sector' UNION ALL
  SELECT 'stock_trade_calendar' UNION ALL
  SELECT 'stock_limit_rule' UNION ALL
  SELECT 'stock_minute_bar' UNION ALL
  SELECT 'stock_kline_day' UNION ALL
  SELECT 'news_source' UNION ALL
  SELECT 'stock_news' UNION ALL
  SELECT 'stock_news_relation' UNION ALL
  SELECT 'user_watchlist_group' UNION ALL
  SELECT 'user_watchlist_item' UNION ALL
  SELECT 'ai_session' UNION ALL
  SELECT 'ai_task' UNION ALL
  SELECT 'ai_task_target' UNION ALL
  SELECT 'ai_context_snapshot' UNION ALL
  SELECT 'ai_message' UNION ALL
  SELECT 'ai_report' UNION ALL
  SELECT 'ai_evidence' UNION ALL
  SELECT 'ai_feedback' UNION ALL
  SELECT 'ai_usage' UNION ALL
  SELECT 'job_execution_summary' UNION ALL
  SELECT 'data_quality_issue' UNION ALL
  SELECT 'event_outbox'
)
SELECT e.table_name AS missing_table
FROM expected_tables e
LEFT JOIN information_schema.tables t
  ON t.table_schema = DATABASE() AND t.table_name = e.table_name
WHERE t.table_name IS NULL;

-- 2. 所有平台表均应使用 InnoDB 与 utf8mb4。
SELECT `table_name`, `engine`, `table_collation`
FROM information_schema.tables
WHERE `table_schema` = DATABASE()
  AND `table_name` NOT IN ('flyway_schema_history')
  AND (`engine` <> 'InnoDB' OR `table_collation` NOT LIKE 'utf8mb4%');

-- 3. 架构约定不使用数据库外键，foreign_key_count 必须为 0。
SELECT COUNT(*) AS foreign_key_count
FROM information_schema.referential_constraints
WHERE constraint_schema = DATABASE();

-- 4. 核心唯一索引必须存在。
WITH expected_indexes AS (
  SELECT 'sys_user' AS table_name, 'uk_sys_user_email' AS index_name UNION ALL
  SELECT 'sys_user_role', 'uk_sys_user_role' UNION ALL
  SELECT 'sys_role_permission', 'uk_sys_role_permission' UNION ALL
  SELECT 'stock_security', 'uk_stock_security_full_symbol' UNION ALL
  SELECT 'stock_minute_bar', 'uk_stock_minute_security_time' UNION ALL
  SELECT 'stock_kline_day', 'uk_stock_kline_day_security_date' UNION ALL
  SELECT 'stock_news', 'uk_stock_news_source_content' UNION ALL
  SELECT 'stock_news_relation', 'uk_news_relation_target' UNION ALL
  SELECT 'user_watchlist_group', 'uk_watchlist_group_user_name' UNION ALL
  SELECT 'user_watchlist_group', 'uk_watchlist_group_default_owner' UNION ALL
  SELECT 'user_watchlist_item', 'uk_watchlist_item_group_security' UNION ALL
  SELECT 'ai_task', 'uk_ai_task_request_id' UNION ALL
  SELECT 'ai_report', 'uk_ai_report_task' UNION ALL
  SELECT 'ai_feedback', 'uk_ai_feedback_report_user' UNION ALL
  SELECT 'event_outbox', 'uk_event_outbox_event_id'
)
SELECT e.table_name, e.index_name AS missing_index
FROM expected_indexes e
LEFT JOIN information_schema.statistics s
  ON s.table_schema = DATABASE()
 AND s.table_name = e.table_name
 AND s.index_name = e.index_name
WHERE s.index_name IS NULL;

-- 5. 新增表的应用关联不应存在孤立记录。
SELECT 'stock_security.exchange_id' AS relation_name, s.`id` AS row_id, s.`exchange_id` AS missing_id
FROM `stock_security` s LEFT JOIN `stock_exchange` e ON e.`id` = s.`exchange_id`
WHERE e.`id` IS NULL
UNION ALL
SELECT 'stock_security_sector.security_id', ss.`id`, ss.`security_id`
FROM `stock_security_sector` ss LEFT JOIN `stock_security` s ON s.`id` = ss.`security_id`
WHERE s.`id` IS NULL
UNION ALL
SELECT 'stock_security_sector.sector_id', ss.`id`, ss.`sector_id`
FROM `stock_security_sector` ss LEFT JOIN `stock_sector` se ON se.`id` = ss.`sector_id`
WHERE se.`id` IS NULL
UNION ALL
SELECT 'user_watchlist_group.user_id', g.`id`, g.`user_id`
FROM `user_watchlist_group` g LEFT JOIN `sys_user` u ON u.`id` = g.`user_id`
WHERE u.`id` IS NULL
UNION ALL
SELECT 'user_watchlist_item.group_id', i.`id`, i.`group_id`
FROM `user_watchlist_item` i LEFT JOIN `user_watchlist_group` g ON g.`id` = i.`group_id`
WHERE g.`id` IS NULL
UNION ALL
SELECT 'user_watchlist_item.security_id', i.`id`, i.`security_id`
FROM `user_watchlist_item` i LEFT JOIN `stock_security` s ON s.`id` = i.`security_id`
WHERE s.`id` IS NULL
UNION ALL
SELECT 'stock_news.source_id', n.`id`, n.`source_id`
FROM `stock_news` n LEFT JOIN `news_source` ns ON ns.`id` = n.`source_id`
WHERE ns.`id` IS NULL
UNION ALL
SELECT 'stock_news_relation.news_id', nr.`id`, nr.`news_id`
FROM `stock_news_relation` nr LEFT JOIN `stock_news` n ON n.`id` = nr.`news_id`
WHERE n.`id` IS NULL
UNION ALL
SELECT 'ai_session.user_id', s.`id`, s.`user_id`
FROM `ai_session` s LEFT JOIN `sys_user` u ON u.`id` = s.`user_id`
WHERE u.`id` IS NULL
UNION ALL
SELECT 'ai_task.session_id', t.`id`, t.`session_id`
FROM `ai_task` t LEFT JOIN `ai_session` s ON s.`id` = t.`session_id`
WHERE s.`id` IS NULL
UNION ALL
SELECT 'ai_task_target.task_id', tt.`id`, tt.`task_id`
FROM `ai_task_target` tt LEFT JOIN `ai_task` t ON t.`id` = tt.`task_id`
WHERE t.`id` IS NULL
UNION ALL
SELECT 'ai_report.task_id', r.`id`, r.`task_id`
FROM `ai_report` r LEFT JOIN `ai_task` t ON t.`id` = r.`task_id`
WHERE t.`id` IS NULL
UNION ALL
SELECT 'ai_evidence.report_id', e.`id`, e.`report_id`
FROM `ai_evidence` e LEFT JOIN `ai_report` r ON r.`id` = e.`report_id`
WHERE r.`id` IS NULL;

-- 5.1 市场、Provider 和 AI 其他直接关联不应存在孤立记录。
SELECT 'data_sync_checkpoint.provider_id' AS relation_name, c.`id` AS row_id, c.`provider_id` AS missing_id
FROM `data_sync_checkpoint` c LEFT JOIN `external_provider` p ON p.`id` = c.`provider_id`
WHERE p.`id` IS NULL
UNION ALL
SELECT 'stock_security_status_history.security_id', h.`id`, h.`security_id`
FROM `stock_security_status_history` h LEFT JOIN `stock_security` s ON s.`id` = h.`security_id`
WHERE s.`id` IS NULL
UNION ALL
SELECT 'stock_minute_bar.security_id', b.`id`, b.`security_id`
FROM `stock_minute_bar` b LEFT JOIN `stock_security` s ON s.`id` = b.`security_id`
WHERE s.`id` IS NULL
UNION ALL
SELECT 'stock_kline_day.security_id', b.`id`, b.`security_id`
FROM `stock_kline_day` b LEFT JOIN `stock_security` s ON s.`id` = b.`security_id`
WHERE s.`id` IS NULL
UNION ALL
SELECT 'news_source.provider_id', ns.`id`, ns.`provider_id`
FROM `news_source` ns LEFT JOIN `external_provider` p ON p.`id` = ns.`provider_id`
WHERE ns.`provider_id` IS NOT NULL AND p.`id` IS NULL
UNION ALL
SELECT 'stock_news.provider_id', n.`id`, n.`provider_id`
FROM `stock_news` n LEFT JOIN `external_provider` p ON p.`id` = n.`provider_id`
WHERE n.`provider_id` IS NOT NULL AND p.`id` IS NULL
UNION ALL
SELECT 'ai_context_snapshot.task_id', c.`id`, c.`task_id`
FROM `ai_context_snapshot` c LEFT JOIN `ai_task` t ON t.`id` = c.`task_id`
WHERE t.`id` IS NULL
UNION ALL
SELECT 'ai_message.session_id', m.`id`, m.`session_id`
FROM `ai_message` m LEFT JOIN `ai_session` s ON s.`id` = m.`session_id`
WHERE s.`id` IS NULL
UNION ALL
SELECT 'ai_message.task_id', m.`id`, m.`task_id`
FROM `ai_message` m LEFT JOIN `ai_task` t ON t.`id` = m.`task_id`
WHERE m.`task_id` IS NOT NULL AND t.`id` IS NULL
UNION ALL
SELECT 'ai_feedback.report_id', f.`id`, f.`report_id`
FROM `ai_feedback` f LEFT JOIN `ai_report` r ON r.`id` = f.`report_id`
WHERE r.`id` IS NULL
UNION ALL
SELECT 'ai_usage.task_id', u.`id`, u.`task_id`
FROM `ai_usage` u LEFT JOIN `ai_task` t ON t.`id` = u.`task_id`
WHERE t.`id` IS NULL;

-- 5.2 多态关联的目标必须存在。
SELECT 'stock_news_relation.SECURITY' AS relation_name, nr.`id` AS row_id, nr.`target_id` AS missing_id
FROM `stock_news_relation` nr LEFT JOIN `stock_security` s ON s.`id` = nr.`target_id`
WHERE nr.`target_type` = 'SECURITY' AND s.`id` IS NULL
UNION ALL
SELECT 'stock_news_relation.SECTOR', nr.`id`, nr.`target_id`
FROM `stock_news_relation` nr LEFT JOIN `stock_sector` s ON s.`id` = nr.`target_id`
WHERE nr.`target_type` = 'SECTOR' AND s.`id` IS NULL
UNION ALL
SELECT 'stock_news_relation.MARKET', nr.`id`, nr.`target_id`
FROM `stock_news_relation` nr LEFT JOIN `stock_exchange` e ON e.`id` = nr.`target_id`
WHERE nr.`target_type` = 'MARKET' AND e.`id` IS NULL
UNION ALL
SELECT 'ai_task_target.SECURITY', tt.`id`, tt.`target_id`
FROM `ai_task_target` tt LEFT JOIN `stock_security` s ON s.`id` = tt.`target_id`
WHERE tt.`target_type` = 'SECURITY' AND s.`id` IS NULL
UNION ALL
SELECT 'ai_task_target.SECTOR', tt.`id`, tt.`target_id`
FROM `ai_task_target` tt LEFT JOIN `stock_sector` s ON s.`id` = tt.`target_id`
WHERE tt.`target_type` = 'SECTOR' AND s.`id` IS NULL
UNION ALL
SELECT 'ai_task_target.MARKET', tt.`id`, tt.`target_id`
FROM `ai_task_target` tt LEFT JOIN `stock_exchange` e ON e.`id` = tt.`target_id`
WHERE tt.`target_type` = 'MARKET' AND e.`id` IS NULL;

-- 5.3 冗余用户和会话字段必须保持一致。
SELECT 'ai_task.user_id' AS relation_name, t.`id` AS row_id, t.`user_id` AS actual_value, s.`user_id` AS expected_value
FROM `ai_task` t JOIN `ai_session` s ON s.`id` = t.`session_id`
WHERE t.`user_id` <> s.`user_id`
UNION ALL
SELECT 'ai_report.session_id', r.`id`, r.`session_id`, t.`session_id`
FROM `ai_report` r JOIN `ai_task` t ON t.`id` = r.`task_id`
WHERE r.`session_id` <> t.`session_id`
UNION ALL
SELECT 'ai_usage.user_id', u.`id`, u.`user_id`, t.`user_id`
FROM `ai_usage` u JOIN `ai_task` t ON t.`id` = u.`task_id`
WHERE u.`user_id` <> t.`user_id`;

-- 6. 自选项冗余 user_id 必须与分组所有者一致。
SELECT i.`id`, i.`user_id` AS item_user_id, g.`user_id` AS group_user_id
FROM `user_watchlist_item` i
JOIN `user_watchlist_group` g ON g.`id` = i.`group_id`
WHERE i.`user_id` <> g.`user_id`;

-- 7. 行情价格关系必须满足 high >= open/close >= low。
SELECT 'stock_minute_bar' AS table_name, `id`
FROM `stock_minute_bar`
WHERE `high_price` < `open_price` OR `high_price` < `close_price`
   OR `low_price` > `open_price` OR `low_price` > `close_price`
   OR `low_price` < 0
UNION ALL
SELECT 'stock_kline_day', `id`
FROM `stock_kline_day`
WHERE `high_price` < `open_price` OR `high_price` < `close_price`
   OR `low_price` > `open_price` OR `low_price` > `close_price`
   OR `low_price` < 0;

-- 8. informational：仍未映射的旧日志引用保留供审计，不应被自动删除。
SELECT `legacy_user_ref`, COUNT(*) AS unmapped_count
FROM `sys_log`
WHERE `legacy_user_ref` IS NOT NULL
  AND TRIM(`legacy_user_ref`) <> ''
  AND `user_id` IS NULL
GROUP BY `legacy_user_ref`
ORDER BY unmapped_count DESC;

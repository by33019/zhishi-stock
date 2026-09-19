-- preflight_existing_schema.sql 的负向测试用例。
--
-- 用途：验证 preflight 脚本**确实能检出** blocking 项，而不只是"在无问题时保持沉默"。
-- 样本库 sql/stock_db.sql 的数据是干净的，直接跑 preflight 只会得到空结果，
-- 无法证明检查逻辑有效——本文件补上这个缺口。
--
-- ⚠️ 仅用于一次性测试库。本脚本会写入违规数据，禁止在真实库执行。
--
-- 用法：
--   1. 起一个仅导入旧库样本、未执行 Flyway 的 MySQL；
--   2. 执行本文件；
--   3. 执行 sql/checks/preflight_existing_schema.sql，确认各 blocking 段均能报出记录。
--
-- 预期结果（执行 preflight 后）：
--   第 2 段  normalized_email = 'preflight.dup@example.com'，duplicate_count = 2
--   第 3 段  role_name = 'PREFLIGHT_DUP_ROLE'，duplicate_count = 2
--   第 4 段  field_name = 'code'，field_value = 'preflight:dup:code'，duplicate_count = 2
--   第 5 段  sys_user_role 中 user_id = 1237361915165020161 的 duplicate_count = 3
--          （样本库本身已有 1 条该关系，本文件再加 2 条）
--   第 6 段  sys_user_role 的 invalid_count 至少为 1
--   第 7 段  legacy_user_ref 出现 'not-a-number' 与 '8888888888888888888'
--
-- ID 统一使用 8e18 量级（8 开头）：大于样本库现有数据，便于区分与清理，
-- 同时未越 bigint 上限 9223372036854775807。
-- 注意不要用 9.9e18 之类的值——会触发 "Out of range value for column 'id'"。

-- blocking：未删除账户的非空邮箱重复
INSERT INTO `sys_user` (`id`, `username`, `password`, `email`, `deleted`) VALUES
  (8000000000000000001, 'pf_dup_a', 'not-a-real-hash', 'preflight.dup@example.com', 1),
  (8000000000000000002, 'pf_dup_b', 'not-a-real-hash', 'preflight.dup@example.com', 1);

-- blocking：未删除角色名称重复
INSERT INTO `sys_role` (`id`, `name`, `deleted`) VALUES
  (8000000000000000011, 'PREFLIGHT_DUP_ROLE', 1),
  (8000000000000000012, 'PREFLIGHT_DUP_ROLE', 1);

-- blocking：未删除权限的编码重复
INSERT INTO `sys_permission` (`id`, `code`, `deleted`) VALUES
  (8000000000000000021, 'preflight:dup:code', 1),
  (8000000000000000022, 'preflight:dup:code', 1);

-- informational：完全重复的关系，V2 会安全清理只保留最小 ID
INSERT INTO `sys_user_role` (`id`, `user_id`, `role_id`) VALUES
  (8000000000000000031, 1237361915165020161, 1237258113002901512),
  (8000000000000000032, 1237361915165020161, 1237258113002901512);

-- informational：空关系没有业务意义，V2 会删除
INSERT INTO `sys_user_role` (`id`, `user_id`, `role_id`) VALUES
  (8000000000000000033, NULL, 1237258113002901512);

-- informational：无法映射为现有用户 ID 的旧日志引用
INSERT INTO `sys_log` (`id`, `user_id`, `username`) VALUES
  (8000000000000000041, 'not-a-number', 'legacy-probe'),
  (8000000000000000042, '8888888888888888888', 'legacy-probe');

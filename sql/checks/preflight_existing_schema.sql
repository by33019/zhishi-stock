-- 已有数据库执行 V2 前的只读检查。
-- “blocking”查询返回记录时，应先由业务负责人处理；脚本本身不会修改数据。

-- 1. 应存在 11 张旧表；missing_count 必须为 0。
SELECT 11 - COUNT(*) AS missing_count
FROM information_schema.tables
WHERE table_schema = DATABASE()
  AND table_name IN (
    'stock_block_rt_info', 'stock_business', 'stock_market_index_info',
    'stock_outer_market_index_info', 'stock_rt_info', 'sys_log',
    'sys_permission', 'sys_role', 'sys_role_permission', 'sys_user',
    'sys_user_role'
  );

-- 2. blocking：未删除账户的非空邮箱重复将阻断唯一索引创建。
SELECT LOWER(TRIM(`email`)) AS normalized_email, COUNT(*) AS duplicate_count
FROM `sys_user`
WHERE `deleted` = 1 AND `email` IS NOT NULL AND TRIM(`email`) <> ''
GROUP BY LOWER(TRIM(`email`))
HAVING COUNT(*) > 1;

-- 3. blocking：未删除角色名称重复将阻断唯一索引创建。
SELECT TRIM(`name`) AS role_name, COUNT(*) AS duplicate_count
FROM `sys_role`
WHERE `deleted` = 1 AND `name` IS NOT NULL AND TRIM(`name`) <> ''
GROUP BY TRIM(`name`)
HAVING COUNT(*) > 1;

-- 4. blocking：未删除权限的编码或授权标识重复将阻断唯一索引创建。
SELECT 'code' AS field_name, TRIM(`code`) AS field_value, COUNT(*) AS duplicate_count
FROM `sys_permission`
WHERE `deleted` = 1 AND `code` IS NOT NULL AND TRIM(`code`) <> ''
GROUP BY TRIM(`code`)
HAVING COUNT(*) > 1
UNION ALL
SELECT 'perms', TRIM(`perms`), COUNT(*)
FROM `sys_permission`
WHERE `deleted` = 1 AND `perms` IS NOT NULL AND TRIM(`perms`) <> ''
GROUP BY TRIM(`perms`)
HAVING COUNT(*) > 1;

-- 5. informational：V2 会安全清理完全重复的关系，仅保留最小 ID。
SELECT `user_id`, `role_id`, COUNT(*) AS duplicate_count
FROM `sys_user_role`
GROUP BY `user_id`, `role_id`
HAVING COUNT(*) > 1;

SELECT `role_id`, `permission_id`, COUNT(*) AS duplicate_count
FROM `sys_role_permission`
GROUP BY `role_id`, `permission_id`
HAVING COUNT(*) > 1;

-- 6. informational：空关系没有业务意义，V2 会删除。
SELECT 'sys_user_role' AS table_name, COUNT(*) AS invalid_count
FROM `sys_user_role`
WHERE `user_id` IS NULL OR `role_id` IS NULL
UNION ALL
SELECT 'sys_role_permission', COUNT(*)
FROM `sys_role_permission`
WHERE `role_id` IS NULL OR `permission_id` IS NULL;

-- 7. informational：无法映射为现有用户 ID 的旧日志引用会保留在 legacy_user_ref。
SELECT `user_id` AS legacy_user_ref, COUNT(*) AS row_count
FROM `sys_log`
WHERE `user_id` IS NOT NULL
  AND TRIM(`user_id`) <> ''
  AND (
    `user_id` NOT REGEXP '^[0-9]{1,19}$'
    OR NOT EXISTS (
      SELECT 1 FROM `sys_user` u
      WHERE u.`id` = CAST(`sys_log`.`user_id` AS UNSIGNED)
    )
  )
GROUP BY `user_id`
ORDER BY row_count DESC;

-- 8. blocking：目标长度为 20，理论上现有 varchar(10) 不应返回记录。
SELECT `stock_code`, `block_label`, CHAR_LENGTH(`block_label`) AS label_length
FROM `stock_business`
WHERE CHAR_LENGTH(`block_label`) > 20;

-- 9. informational：旧关联表中的孤立关系由业务决定是否清理，本迁移不静默删除。
SELECT 'sys_user_role.user_id' AS relation_name, ur.`id`, ur.`user_id` AS missing_id
FROM `sys_user_role` ur
LEFT JOIN `sys_user` u ON u.`id` = ur.`user_id`
WHERE ur.`user_id` IS NOT NULL AND u.`id` IS NULL
UNION ALL
SELECT 'sys_user_role.role_id', ur.`id`, ur.`role_id`
FROM `sys_user_role` ur
LEFT JOIN `sys_role` r ON r.`id` = ur.`role_id`
WHERE ur.`role_id` IS NOT NULL AND r.`id` IS NULL
UNION ALL
SELECT 'sys_role_permission.role_id', rp.`id`, rp.`role_id`
FROM `sys_role_permission` rp
LEFT JOIN `sys_role` r ON r.`id` = rp.`role_id`
WHERE rp.`role_id` IS NOT NULL AND r.`id` IS NULL
UNION ALL
SELECT 'sys_role_permission.permission_id', rp.`id`, rp.`permission_id`
FROM `sys_role_permission` rp
LEFT JOIN `sys_permission` p ON p.`id` = rp.`permission_id`
WHERE rp.`permission_id` IS NOT NULL AND p.`id` IS NULL;

-- 修复旧结构的字符集、字段不一致、幂等约束与常用查询索引。
-- 执行前先运行 sql/checks/preflight_existing_schema.sql，并备份数据库。

ALTER TABLE `stock_block_rt_info` ROW_FORMAT=DYNAMIC, CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;
ALTER TABLE `stock_business` ROW_FORMAT=DYNAMIC, CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;
ALTER TABLE `stock_market_index_info` ROW_FORMAT=DYNAMIC, CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;
ALTER TABLE `stock_outer_market_index_info` ROW_FORMAT=DYNAMIC, CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;
ALTER TABLE `stock_rt_info` ROW_FORMAT=DYNAMIC, CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;
ALTER TABLE `sys_log` ROW_FORMAT=DYNAMIC, CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;
ALTER TABLE `sys_permission` ROW_FORMAT=DYNAMIC, CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;
ALTER TABLE `sys_role` ROW_FORMAT=DYNAMIC, CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;
ALTER TABLE `sys_role_permission` ROW_FORMAT=DYNAMIC, CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;
ALTER TABLE `sys_user` ROW_FORMAT=DYNAMIC, CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;
ALTER TABLE `sys_user_role` ROW_FORMAT=DYNAMIC, CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;

ALTER TABLE `stock_business`
  MODIFY COLUMN `block_label` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '所属行业板块标识',
  MODIFY COLUMN `stock_name` varchar(40) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT '' COMMENT '股票名称',
  ADD INDEX `idx_stock_business_block_label` (`block_label`);

ALTER TABLE `stock_rt_info`
  MODIFY COLUMN `stock_name` varchar(40) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '股票名称',
  ADD INDEX `idx_stock_rt_code_time` (`stock_code`, `cur_time` DESC);

ALTER TABLE `stock_market_index_info`
  ADD INDEX `idx_market_index_code_time` (`market_code`, `cur_time` DESC);

ALTER TABLE `stock_outer_market_index_info`
  ADD INDEX `idx_outer_index_code_time` (`market_code`, `cur_time` DESC);

ALTER TABLE `stock_block_rt_info`
  MODIFY COLUMN `block_name` varchar(40) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '板块名称',
  ADD INDEX `idx_stock_block_label_time` (`label`, `cur_time` DESC);

-- 保留旧字符串引用，新增类型一致的用户 ID。只有可安全匹配的历史记录会被回填。
ALTER TABLE `sys_log`
  CHANGE COLUMN `user_id` `legacy_user_ref` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '迁移前的用户引用，仅兼容审计',
  ADD COLUMN `user_id` bigint DEFAULT NULL COMMENT '用户 ID' AFTER `id`,
  ADD COLUMN `trace_id` varchar(64) DEFAULT NULL COMMENT '请求追踪 ID' AFTER `ip`,
  ADD COLUMN `request_uri` varchar(255) DEFAULT NULL COMMENT '请求 URI' AFTER `method`,
  ADD COLUMN `http_method` varchar(10) DEFAULT NULL COMMENT 'HTTP 方法' AFTER `request_uri`,
  ADD COLUMN `result_status` varchar(32) DEFAULT NULL COMMENT 'SUCCESS、FAILURE 或 DENIED' AFTER `http_method`,
  MODIFY COLUMN `params` text DEFAULT NULL COMMENT '脱敏后的参数摘要',
  ADD INDEX `idx_sys_log_user_time` (`user_id`, `create_time` DESC),
  ADD INDEX `idx_sys_log_trace_id` (`trace_id`),
  ADD INDEX `idx_sys_log_create_time` (`create_time` DESC);

UPDATE `sys_log` l
JOIN `sys_user` u
  ON l.`legacy_user_ref` REGEXP '^[0-9]{1,19}$'
 AND u.`id` = CAST(l.`legacy_user_ref` AS UNSIGNED)
SET l.`user_id` = u.`id`
WHERE l.`legacy_user_ref` IS NOT NULL;

-- 空邮箱不参与唯一性约束；真实重复邮箱由前置检查阻断，避免静默覆盖用户资料。
UPDATE `sys_user`
SET `email` = NULL
WHERE `email` IS NOT NULL AND TRIM(`email`) = '';

ALTER TABLE `sys_user`
  MODIFY COLUMN `email` varchar(254) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '邮箱，非空时唯一',
  ADD COLUMN `active_email` varchar(254) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci GENERATED ALWAYS AS (
    CASE WHEN `deleted` = 1 THEN `email` ELSE NULL END
  ) STORED COMMENT '用于约束未删除账户邮箱唯一' AFTER `email`,
  ADD COLUMN `token_version` int NOT NULL DEFAULT 0 COMMENT 'JWT 权限和会话版本' AFTER `status`,
  ADD COLUMN `password_changed_at` datetime(3) DEFAULT NULL COMMENT '最近密码变更时间' AFTER `password`,
  ADD COLUMN `last_login_time` datetime(3) DEFAULT NULL COMMENT '最近登录时间' AFTER `update_time`,
  ADD COLUMN `version` int NOT NULL DEFAULT 0 COMMENT '乐观锁版本' AFTER `last_login_time`,
  ADD UNIQUE INDEX `uk_sys_user_email` (`active_email`),
  ADD INDEX `idx_sys_user_status_deleted` (`status`, `deleted`);

UPDATE `sys_role` SET `name` = NULL WHERE `name` IS NOT NULL AND TRIM(`name`) = '';
UPDATE `sys_permission` SET `code` = NULL WHERE `code` IS NOT NULL AND TRIM(`code`) = '';
UPDATE `sys_permission` SET `perms` = NULL WHERE `perms` IS NOT NULL AND TRIM(`perms`) = '';

ALTER TABLE `sys_role`
  ADD COLUMN `active_name` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci GENERATED ALWAYS AS (
    CASE WHEN `deleted` = 1 THEN `name` ELSE NULL END
  ) STORED COMMENT '用于约束未删除角色名称唯一' AFTER `name`,
  ADD COLUMN `version` int NOT NULL DEFAULT 0 COMMENT '乐观锁版本' AFTER `update_time`,
  ADD UNIQUE INDEX `uk_sys_role_name` (`active_name`),
  ADD INDEX `idx_sys_role_status_deleted` (`status`, `deleted`);

ALTER TABLE `sys_permission`
  MODIFY COLUMN `type` tinyint unsigned DEFAULT 1 COMMENT '1目录、2菜单、3按钮',
  ADD COLUMN `active_code` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci GENERATED ALWAYS AS (
    CASE WHEN `deleted` = 1 THEN `code` ELSE NULL END
  ) STORED COMMENT '用于约束未删除权限编码唯一' AFTER `code`,
  ADD COLUMN `active_perms` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci GENERATED ALWAYS AS (
    CASE WHEN `deleted` = 1 THEN `perms` ELSE NULL END
  ) STORED COMMENT '用于约束未删除授权标识唯一' AFTER `perms`,
  ADD COLUMN `version` int NOT NULL DEFAULT 0 COMMENT '乐观锁版本' AFTER `update_time`,
  ADD UNIQUE INDEX `uk_sys_permission_code` (`active_code`),
  ADD UNIQUE INDEX `uk_sys_permission_perms` (`active_perms`),
  ADD INDEX `idx_sys_permission_tree` (`pid`, `order_num`),
  ADD INDEX `idx_sys_permission_status_deleted` (`status`, `deleted`);

-- 完全重复的角色关系没有独立业务含义，保留最小 ID 后再建立幂等约束。
DELETE ur1
FROM `sys_user_role` ur1
JOIN `sys_user_role` ur2
  ON ur1.`user_id` = ur2.`user_id`
 AND ur1.`role_id` = ur2.`role_id`
 AND ur1.`id` > ur2.`id`;

DELETE FROM `sys_user_role` WHERE `user_id` IS NULL OR `role_id` IS NULL;

ALTER TABLE `sys_user_role`
  MODIFY COLUMN `user_id` bigint NOT NULL COMMENT '用户 ID',
  MODIFY COLUMN `role_id` bigint NOT NULL COMMENT '角色 ID',
  ADD UNIQUE INDEX `uk_sys_user_role` (`user_id`, `role_id`),
  ADD INDEX `idx_sys_user_role_role` (`role_id`, `user_id`);

DELETE rp1
FROM `sys_role_permission` rp1
JOIN `sys_role_permission` rp2
  ON rp1.`role_id` = rp2.`role_id`
 AND rp1.`permission_id` = rp2.`permission_id`
 AND rp1.`id` > rp2.`id`;

DELETE FROM `sys_role_permission` WHERE `role_id` IS NULL OR `permission_id` IS NULL;

ALTER TABLE `sys_role_permission`
  MODIFY COLUMN `role_id` bigint NOT NULL COMMENT '角色 ID',
  MODIFY COLUMN `permission_id` bigint NOT NULL COMMENT '权限 ID',
  ADD UNIQUE INDEX `uk_sys_role_permission` (`role_id`, `permission_id`),
  ADD INDEX `idx_sys_role_permission_permission` (`permission_id`, `role_id`);

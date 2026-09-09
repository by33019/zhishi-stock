-- 用户自选分组和自选证券数据域。

CREATE TABLE `user_watchlist_group` (
  `id` bigint NOT NULL COMMENT '自选分组 ID，由应用生成',
  `user_id` bigint NOT NULL COMMENT '所属用户 ID，应用层维护关联',
  `group_name` varchar(40) NOT NULL COMMENT '分组名称，应用限制 1 至 20 个字符',
  `sort_no` int unsigned NOT NULL DEFAULT 0,
  `is_default` tinyint unsigned NOT NULL DEFAULT 0 COMMENT '1为系统默认自选分组',
  `version` int unsigned NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
  `deleted_at` datetime(3) DEFAULT NULL COMMENT '软删除时间，NULL 表示有效',
  `active_group_name` varchar(40) GENERATED ALWAYS AS (
    CASE WHEN `deleted_at` IS NULL THEN `group_name` ELSE NULL END
  ) STORED COMMENT '用于约束活动分组名称唯一',
  `default_owner_id` bigint GENERATED ALWAYS AS (
    CASE WHEN `deleted_at` IS NULL AND `is_default` = 1 THEN `user_id` ELSE NULL END
  ) STORED COMMENT '用于约束每个用户只有一个有效默认分组',
  `created_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `updated_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE INDEX `uk_watchlist_group_user_name` (`user_id`, `active_group_name`),
  UNIQUE INDEX `uk_watchlist_group_default_owner` (`default_owner_id`),
  INDEX `idx_watchlist_group_user_sort` (`user_id`, `deleted_at`, `sort_no`, `id`),
  CONSTRAINT `ck_watchlist_group_default` CHECK (`is_default` IN (0, 1)),
  CONSTRAINT `ck_watchlist_group_name` CHECK (CHAR_LENGTH(TRIM(`group_name`)) BETWEEN 1 AND 20)
) ENGINE=InnoDB DEFAULT CHARACTER SET=utf8mb4 COLLATE=utf8mb4_general_ci ROW_FORMAT=DYNAMIC COMMENT='用户自选分组';

CREATE TABLE `user_watchlist_item` (
  `id` bigint NOT NULL COMMENT '自选项 ID，由应用生成',
  `user_id` bigint NOT NULL COMMENT '所属用户 ID，冗余用于隔离和查询',
  `group_id` bigint NOT NULL COMMENT '自选分组 ID，应用层维护关联',
  `security_id` bigint NOT NULL COMMENT '证券 ID，应用层维护关联',
  `sort_no` int unsigned NOT NULL DEFAULT 0,
  `version` int unsigned NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
  `created_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `updated_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE INDEX `uk_watchlist_item_group_security` (`group_id`, `security_id`),
  INDEX `idx_watchlist_item_user_group_sort` (`user_id`, `group_id`, `sort_no`, `id`),
  INDEX `idx_watchlist_item_user_security` (`user_id`, `security_id`)
) ENGINE=InnoDB DEFAULT CHARACTER SET=utf8mb4 COLLATE=utf8mb4_general_ci ROW_FORMAT=DYNAMIC COMMENT='用户自选证券';

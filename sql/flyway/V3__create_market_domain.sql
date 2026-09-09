-- 市场、证券主数据、交易规则和规范 K 线数据域。
-- 所有 ID 均由应用使用 MyBatis-Plus ASSIGN_ID/Snowflake 生成。

CREATE TABLE `external_provider` (
  `id` bigint NOT NULL COMMENT 'Provider ID，由应用生成',
  `provider_code` varchar(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '内部唯一编码',
  `provider_type` varchar(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'QUOTE、NEWS 或 LLM',
  `provider_name` varchar(100) NOT NULL COMMENT '显示名称',
  `status` varchar(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'ENABLED' COMMENT 'ENABLED、DEGRADED 或 DISABLED',
  `credential_config_key` varchar(128) CHARACTER SET ascii COLLATE ascii_bin DEFAULT NULL COMMENT '外部密钥配置引用，不保存密钥值',
  `rate_limit_policy` json DEFAULT NULL COMMENT '套餐额度和限流策略',
  `last_health_status` varchar(16) CHARACTER SET ascii COLLATE ascii_bin DEFAULT NULL COMMENT '最近健康状态',
  `last_health_at` datetime(3) DEFAULT NULL COMMENT '最近健康检查时间',
  `last_success_at` datetime(3) DEFAULT NULL COMMENT '最近成功调用时间',
  `consecutive_failures` int unsigned NOT NULL DEFAULT 0 COMMENT '连续失败次数',
  `version` int unsigned NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
  `created_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `updated_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE INDEX `uk_external_provider_code` (`provider_code`),
  INDEX `idx_external_provider_type_status` (`provider_type`, `status`),
  CONSTRAINT `ck_external_provider_status` CHECK (`status` IN ('ENABLED', 'DEGRADED', 'DISABLED')),
  CONSTRAINT `ck_external_provider_type` CHECK (`provider_type` IN ('QUOTE', 'NEWS', 'LLM'))
) ENGINE=InnoDB DEFAULT CHARACTER SET=utf8mb4 COLLATE=utf8mb4_general_ci ROW_FORMAT=DYNAMIC COMMENT='外部服务 Provider 元数据';

CREATE TABLE `data_sync_checkpoint` (
  `id` bigint NOT NULL COMMENT '同步游标 ID，由应用生成',
  `provider_id` bigint NOT NULL COMMENT '外部 Provider ID，应用层维护关联',
  `task_type` varchar(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'SECURITY、QUOTE、SECTOR、INDEX、NEWS 等',
  `scope_key` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL DEFAULT 'GLOBAL' COMMENT '市场、分片或来源范围',
  `cursor_value` text DEFAULT NULL COMMENT '增量游标，不保存凭证',
  `last_source_time` datetime(3) DEFAULT NULL COMMENT '已处理的最新源数据时间',
  `last_attempt_at` datetime(3) DEFAULT NULL COMMENT '最近尝试时间',
  `last_success_at` datetime(3) DEFAULT NULL COMMENT '最近成功时间',
  `status` varchar(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'READY' COMMENT 'READY、RUNNING、FAILED 或 PAUSED',
  `consecutive_failures` int unsigned NOT NULL DEFAULT 0,
  `last_error_code` varchar(64) CHARACTER SET ascii COLLATE ascii_bin DEFAULT NULL,
  `version` int unsigned NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
  `created_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `updated_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE INDEX `uk_sync_checkpoint_scope` (`provider_id`, `task_type`, `scope_key`),
  INDEX `idx_sync_checkpoint_status_attempt` (`status`, `last_attempt_at`),
  CONSTRAINT `ck_sync_checkpoint_status` CHECK (`status` IN ('READY', 'RUNNING', 'FAILED', 'PAUSED'))
) ENGINE=InnoDB DEFAULT CHARACTER SET=utf8mb4 COLLATE=utf8mb4_general_ci ROW_FORMAT=DYNAMIC COMMENT='第三方数据增量同步游标';

CREATE TABLE `stock_exchange` (
  `id` bigint NOT NULL COMMENT '交易所 ID，由应用生成',
  `exchange_code` varchar(8) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'SH、SZ、BJ 等内部编码',
  `exchange_name` varchar(64) NOT NULL COMMENT '交易所名称',
  `country_code` char(2) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'CN' COMMENT 'ISO 3166-1 alpha-2',
  `timezone` varchar(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'Asia/Shanghai',
  `currency_code` char(3) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'CNY',
  `status` varchar(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE 或 INACTIVE',
  `created_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `updated_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE INDEX `uk_stock_exchange_code` (`exchange_code`),
  CONSTRAINT `ck_stock_exchange_status` CHECK (`status` IN ('ACTIVE', 'INACTIVE'))
) ENGINE=InnoDB DEFAULT CHARACTER SET=utf8mb4 COLLATE=utf8mb4_general_ci ROW_FORMAT=DYNAMIC COMMENT='证券交易所主数据';

CREATE TABLE `stock_security` (
  `id` bigint NOT NULL COMMENT '证券 ID，由应用生成',
  `exchange_id` bigint NOT NULL COMMENT '交易所 ID，应用层维护关联',
  `exchange_code` varchar(8) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '交易所编码快照',
  `security_code` varchar(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '交易所内证券代码',
  `full_symbol` varchar(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '全局代码，如 SH.600000',
  `security_name` varchar(64) NOT NULL COMMENT '证券名称',
  `short_name` varchar(32) DEFAULT NULL COMMENT '证券简称',
  `pinyin` varchar(128) CHARACTER SET ascii COLLATE ascii_general_ci DEFAULT NULL COMMENT '名称拼音',
  `pinyin_abbr` varchar(32) CHARACTER SET ascii COLLATE ascii_general_ci DEFAULT NULL COMMENT '拼音首字母',
  `security_type` varchar(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'STOCK' COMMENT 'STOCK、ETF、INDEX 等',
  `board_code` varchar(32) CHARACTER SET ascii COLLATE ascii_bin DEFAULT NULL COMMENT '主板、科创板、创业板等规则板块编码',
  `listing_status` varchar(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'LISTED' COMMENT 'LISTED、SUSPENDED、DELISTED 或 PRELISTED',
  `listed_date` date DEFAULT NULL,
  `delisted_date` date DEFAULT NULL,
  `is_st` tinyint unsigned NOT NULL DEFAULT 0 COMMENT '是否特殊处理股票',
  `is_suspended` tinyint unsigned NOT NULL DEFAULT 0 COMMENT '当前是否停牌',
  `price_scale` tinyint unsigned NOT NULL DEFAULT 2 COMMENT '行情显示小数位',
  `lot_size` int unsigned NOT NULL DEFAULT 100 COMMENT '默认交易单位，仅作展示规则',
  `source_code` varchar(32) CHARACTER SET ascii COLLATE ascii_bin DEFAULT NULL COMMENT '最近更新来源',
  `source_updated_at` datetime(3) DEFAULT NULL COMMENT '来源数据更新时间',
  `version` int unsigned NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
  `created_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `updated_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE INDEX `uk_stock_security_exchange_code` (`exchange_code`, `security_code`),
  UNIQUE INDEX `uk_stock_security_full_symbol` (`full_symbol`),
  INDEX `idx_stock_security_name` (`security_name`),
  INDEX `idx_stock_security_pinyin_abbr` (`pinyin_abbr`),
  INDEX `idx_stock_security_status` (`security_type`, `listing_status`, `is_suspended`),
  INDEX `idx_stock_security_board` (`board_code`, `listing_status`),
  CONSTRAINT `ck_stock_security_flags` CHECK (`is_st` IN (0, 1) AND `is_suspended` IN (0, 1)),
  CONSTRAINT `ck_stock_security_dates` CHECK (`delisted_date` IS NULL OR `listed_date` IS NULL OR `delisted_date` >= `listed_date`)
) ENGINE=InnoDB DEFAULT CHARACTER SET=utf8mb4 COLLATE=utf8mb4_general_ci ROW_FORMAT=DYNAMIC COMMENT='证券统一主数据';

CREATE TABLE `stock_security_status_history` (
  `id` bigint NOT NULL COMMENT '状态历史 ID，由应用生成',
  `security_id` bigint NOT NULL COMMENT '证券 ID，应用层维护关联',
  `status_type` varchar(24) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'LISTING、ST 或 SUSPENSION',
  `status_value` varchar(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '该时段内状态值',
  `effective_from` datetime(3) NOT NULL COMMENT '生效时间，闭区间',
  `effective_to` datetime(3) DEFAULT NULL COMMENT '失效时间，开区间；NULL 表示当前有效',
  `reason` varchar(500) DEFAULT NULL COMMENT '状态原因摘要',
  `source_code` varchar(32) CHARACTER SET ascii COLLATE ascii_bin DEFAULT NULL,
  `source_reference` varchar(128) DEFAULT NULL COMMENT '来源记录引用',
  `created_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE INDEX `uk_security_status_effective` (`security_id`, `status_type`, `effective_from`),
  INDEX `idx_security_status_current` (`security_id`, `status_type`, `effective_to`),
  INDEX `idx_security_status_time` (`effective_from`, `effective_to`),
  CONSTRAINT `ck_security_status_period` CHECK (`effective_to` IS NULL OR `effective_to` > `effective_from`)
) ENGINE=InnoDB DEFAULT CHARACTER SET=utf8mb4 COLLATE=utf8mb4_general_ci ROW_FORMAT=DYNAMIC COMMENT='证券状态生效历史';

CREATE TABLE `stock_sector` (
  `id` bigint NOT NULL COMMENT '板块 ID，由应用生成',
  `sector_code` varchar(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '全局板块编码',
  `sector_name` varchar(64) NOT NULL COMMENT '板块名称',
  `sector_type` varchar(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'INDUSTRY、CONCEPT 或 REGION',
  `parent_id` bigint DEFAULT NULL COMMENT '父板块 ID，应用层维护关联',
  `level_no` tinyint unsigned NOT NULL DEFAULT 1 COMMENT '层级，从 1 开始',
  `status` varchar(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'ACTIVE',
  `source_code` varchar(32) CHARACTER SET ascii COLLATE ascii_bin DEFAULT NULL,
  `source_updated_at` datetime(3) DEFAULT NULL,
  `version` int unsigned NOT NULL DEFAULT 0,
  `created_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `updated_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE INDEX `uk_stock_sector_code` (`sector_code`),
  INDEX `idx_stock_sector_type_status` (`sector_type`, `status`),
  INDEX `idx_stock_sector_parent` (`parent_id`, `level_no`),
  CONSTRAINT `ck_stock_sector_type` CHECK (`sector_type` IN ('INDUSTRY', 'CONCEPT', 'REGION')),
  CONSTRAINT `ck_stock_sector_status` CHECK (`status` IN ('ACTIVE', 'INACTIVE'))
) ENGINE=InnoDB DEFAULT CHARACTER SET=utf8mb4 COLLATE=utf8mb4_general_ci ROW_FORMAT=DYNAMIC COMMENT='行业、概念和地域板块主数据';

CREATE TABLE `stock_security_sector` (
  `id` bigint NOT NULL COMMENT '关系 ID，由应用生成',
  `security_id` bigint NOT NULL COMMENT '证券 ID，应用层维护关联',
  `sector_id` bigint NOT NULL COMMENT '板块 ID，应用层维护关联',
  `relation_type` varchar(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'MEMBER' COMMENT 'PRIMARY、SECONDARY 或 MEMBER',
  `is_primary` tinyint unsigned NOT NULL DEFAULT 0,
  `effective_from` date NOT NULL COMMENT '关系生效日期',
  `effective_to` date DEFAULT NULL COMMENT '关系失效日期，NULL 表示当前有效',
  `source_code` varchar(32) CHARACTER SET ascii COLLATE ascii_bin DEFAULT NULL,
  `created_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `updated_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE INDEX `uk_security_sector_effective` (`security_id`, `sector_id`, `effective_from`),
  INDEX `idx_security_sector_current` (`security_id`, `effective_to`, `is_primary`),
  INDEX `idx_sector_security_current` (`sector_id`, `effective_to`, `security_id`),
  CONSTRAINT `ck_security_sector_primary` CHECK (`is_primary` IN (0, 1)),
  CONSTRAINT `ck_security_sector_period` CHECK (`effective_to` IS NULL OR `effective_to` >= `effective_from`)
) ENGINE=InnoDB DEFAULT CHARACTER SET=utf8mb4 COLLATE=utf8mb4_general_ci ROW_FORMAT=DYNAMIC COMMENT='证券与板块关系';

CREATE TABLE `stock_trade_calendar` (
  `id` bigint NOT NULL COMMENT '交易日历 ID，由应用生成',
  `exchange_code` varchar(8) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `trade_date` date NOT NULL,
  `is_trading_day` tinyint unsigned NOT NULL COMMENT '1交易日、0非交易日',
  `previous_trade_date` date DEFAULT NULL,
  `next_trade_date` date DEFAULT NULL,
  `session_definition` json DEFAULT NULL COMMENT '当日集合竞价、连续竞价和休市时段',
  `source_code` varchar(32) CHARACTER SET ascii COLLATE ascii_bin DEFAULT NULL,
  `source_updated_at` datetime(3) DEFAULT NULL,
  `version` int unsigned NOT NULL DEFAULT 0,
  `created_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `updated_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE INDEX `uk_trade_calendar_exchange_date` (`exchange_code`, `trade_date`),
  INDEX `idx_trade_calendar_date_status` (`trade_date`, `is_trading_day`),
  CONSTRAINT `ck_trade_calendar_flag` CHECK (`is_trading_day` IN (0, 1))
) ENGINE=InnoDB DEFAULT CHARACTER SET=utf8mb4 COLLATE=utf8mb4_general_ci ROW_FORMAT=DYNAMIC COMMENT='交易日历与当日交易时段';

CREATE TABLE `stock_limit_rule` (
  `id` bigint NOT NULL COMMENT '涨跌停规则 ID，由应用生成',
  `rule_code` varchar(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '规则版本唯一编码',
  `exchange_code` varchar(8) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `board_code` varchar(32) CHARACTER SET ascii COLLATE ascii_bin DEFAULT NULL COMMENT '为空表示交易所默认规则',
  `security_type` varchar(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'STOCK',
  `special_status` varchar(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'NORMAL' COMMENT 'NORMAL、ST 或其他规则状态',
  `min_listing_days` int unsigned DEFAULT NULL COMMENT '上市后最小自然日数条件',
  `max_listing_days` int unsigned DEFAULT NULL COMMENT '上市后最大自然日数条件',
  `upper_limit_rate` decimal(12,8) DEFAULT NULL COMMENT '涨停比例，0.10 表示 10%',
  `lower_limit_rate` decimal(12,8) DEFAULT NULL COMMENT '跌停绝对比例，0.10 表示 10%',
  `no_price_limit` tinyint unsigned NOT NULL DEFAULT 0 COMMENT '1表示该规则期间不设涨跌幅限制',
  `effective_from` date NOT NULL,
  `effective_to` date DEFAULT NULL,
  `priority_no` int NOT NULL DEFAULT 100 COMMENT '数值越小优先级越高',
  `source_code` varchar(32) CHARACTER SET ascii COLLATE ascii_bin DEFAULT NULL,
  `source_reference` varchar(255) DEFAULT NULL,
  `version` int unsigned NOT NULL DEFAULT 0,
  `created_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `updated_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE INDEX `uk_stock_limit_rule_code` (`rule_code`),
  INDEX `idx_stock_limit_rule_match` (`exchange_code`, `board_code`, `security_type`, `special_status`, `effective_from`, `effective_to`, `priority_no`),
  CONSTRAINT `ck_stock_limit_rule_flag` CHECK (`no_price_limit` IN (0, 1)),
  CONSTRAINT `ck_stock_limit_rule_period` CHECK (`effective_to` IS NULL OR `effective_to` >= `effective_from`),
  CONSTRAINT `ck_stock_limit_rule_rates` CHECK (
    (`no_price_limit` = 1 AND `upper_limit_rate` IS NULL AND `lower_limit_rate` IS NULL)
    OR (`no_price_limit` = 0 AND `upper_limit_rate` > 0 AND `lower_limit_rate` > 0)
  )
) ENGINE=InnoDB DEFAULT CHARACTER SET=utf8mb4 COLLATE=utf8mb4_general_ci ROW_FORMAT=DYNAMIC COMMENT='版本化证券涨跌停规则';

CREATE TABLE `stock_minute_bar` (
  `id` bigint NOT NULL COMMENT '分钟 K 线 ID，由应用生成',
  `security_id` bigint NOT NULL COMMENT '证券 ID，应用层维护关联',
  `trade_date` date NOT NULL COMMENT '所属交易日',
  `bar_time` datetime NOT NULL COMMENT '分钟开始时间，精确到分钟',
  `open_price` decimal(18,6) NOT NULL,
  `high_price` decimal(18,6) NOT NULL,
  `low_price` decimal(18,6) NOT NULL,
  `close_price` decimal(18,6) NOT NULL,
  `trade_volume` bigint unsigned NOT NULL DEFAULT 0 COMMENT '成交量，统一为股',
  `trade_amount` decimal(24,4) NOT NULL DEFAULT 0 COMMENT '成交金额，人民币元',
  `trade_count` bigint unsigned DEFAULT NULL COMMENT '可用时保存逐笔成交数',
  `vwap_price` decimal(18,6) DEFAULT NULL COMMENT '成交量加权平均价',
  `source_code` varchar(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `source_updated_at` datetime(3) DEFAULT NULL,
  `quality_status` varchar(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'VALID' COMMENT 'VALID、DELAYED 或 CORRECTED',
  `created_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `updated_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE INDEX `uk_stock_minute_security_time` (`security_id`, `bar_time`),
  INDEX `idx_stock_minute_trade_date_security` (`trade_date`, `security_id`, `bar_time`),
  INDEX `idx_stock_minute_source_time` (`source_code`, `source_updated_at`),
  CONSTRAINT `ck_stock_minute_price` CHECK (
    `open_price` >= 0 AND `high_price` >= `open_price` AND `high_price` >= `close_price`
    AND `low_price` <= `open_price` AND `low_price` <= `close_price` AND `low_price` >= 0
  ),
  CONSTRAINT `ck_stock_minute_quality` CHECK (`quality_status` IN ('VALID', 'DELAYED', 'CORRECTED'))
) ENGINE=InnoDB DEFAULT CHARACTER SET=utf8mb4 COLLATE=utf8mb4_general_ci ROW_FORMAT=DYNAMIC COMMENT='证券规范 1 分钟 OHLCV';

CREATE TABLE `stock_kline_day` (
  `id` bigint NOT NULL COMMENT '日 K ID，由应用生成',
  `security_id` bigint NOT NULL COMMENT '证券 ID，应用层维护关联',
  `trade_date` date NOT NULL,
  `adjustment_type` varchar(8) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'NONE' COMMENT 'NONE、FORWARD 或 BACKWARD',
  `pre_close_price` decimal(18,6) DEFAULT NULL,
  `open_price` decimal(18,6) NOT NULL,
  `high_price` decimal(18,6) NOT NULL,
  `low_price` decimal(18,6) NOT NULL,
  `close_price` decimal(18,6) NOT NULL,
  `change_amount` decimal(18,6) DEFAULT NULL,
  `change_rate` decimal(12,8) DEFAULT NULL COMMENT '0.01 表示 1%',
  `amplitude_rate` decimal(12,8) DEFAULT NULL,
  `trade_volume` bigint unsigned NOT NULL DEFAULT 0 COMMENT '成交量，统一为股',
  `trade_amount` decimal(24,4) NOT NULL DEFAULT 0 COMMENT '成交金额，人民币元',
  `turnover_rate` decimal(12,8) DEFAULT NULL,
  `source_code` varchar(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `quality_status` varchar(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'VALID',
  `source_updated_at` datetime(3) DEFAULT NULL,
  `created_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `updated_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE INDEX `uk_stock_kline_day_security_date` (`security_id`, `trade_date`, `adjustment_type`),
  INDEX `idx_stock_kline_day_date` (`trade_date`, `security_id`),
  CONSTRAINT `ck_stock_kline_day_price` CHECK (
    `open_price` >= 0 AND `high_price` >= `open_price` AND `high_price` >= `close_price`
    AND `low_price` <= `open_price` AND `low_price` <= `close_price` AND `low_price` >= 0
  ),
  CONSTRAINT `ck_stock_kline_day_adjustment` CHECK (`adjustment_type` IN ('NONE', 'FORWARD', 'BACKWARD')),
  CONSTRAINT `ck_stock_kline_day_quality` CHECK (`quality_status` IN ('VALID', 'DELAYED', 'CORRECTED'))
) ENGINE=InnoDB DEFAULT CHARACTER SET=utf8mb4 COLLATE=utf8mb4_general_ci ROW_FORMAT=DYNAMIC COMMENT='证券日 K 线';

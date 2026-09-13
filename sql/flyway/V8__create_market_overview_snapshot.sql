-- 市场总览聚合快照：为 Redis 缺失或不可用时提供最近有效快照降级。
CREATE TABLE `market_overview_snapshot` (
  `id` bigint NOT NULL COMMENT '主键 ID，由应用生成',
  `market_code` varchar(16) NOT NULL COMMENT '市场代码',
  `trade_date` date NOT NULL COMMENT '交易日期',
  `data_time` datetime(3) NOT NULL COMMENT '行情数据时间',
  `data_status` varchar(16) NOT NULL COMMENT '写入时数据状态',
  `snapshot_version` varchar(64) NOT NULL COMMENT '快照版本',
  `snapshot_json` longtext NOT NULL COMMENT '标准化市场总览 JSON',
  `created_at` datetime(3) NOT NULL COMMENT '创建时间',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uk_market_overview_version` (`market_code`, `snapshot_version`) USING BTREE,
  INDEX `idx_market_overview_latest` (`market_code`, `data_time`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARACTER SET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='市场总览聚合快照' ROW_FORMAT=DYNAMIC;

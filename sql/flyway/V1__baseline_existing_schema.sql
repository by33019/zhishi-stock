-- AI 智能股票分析平台旧结构基线
-- 空数据库从本文件开始执行；已有 stock_db.sql 数据库应 baseline 到版本 1，禁止重复执行本文件。
-- 本基线只包含原项目 11 张表的结构，不包含任何业务初始化数据。

CREATE TABLE `stock_block_rt_info` (
  `id` bigint NOT NULL COMMENT '板块主键 ID，由应用生成',
  `label` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '板块标识',
  `block_name` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '板块名称',
  `company_num` int DEFAULT NULL COMMENT '公司数量',
  `avg_price` decimal(10,3) DEFAULT NULL COMMENT '平均价格',
  `updown_rate` decimal(10,3) DEFAULT NULL COMMENT '涨跌幅',
  `trade_amount` bigint DEFAULT NULL COMMENT '成交量',
  `trade_volume` decimal(18,3) DEFAULT NULL COMMENT '成交金额',
  `cur_time` datetime DEFAULT NULL COMMENT '行情时间',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `unique_name_time` (`cur_time`, `label`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARACTER SET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='股票板块详情信息表' ROW_FORMAT=COMPACT;

CREATE TABLE `stock_business` (
  `stock_code` char(6) CHARACTER SET utf8 COLLATE utf8_general_ci NOT NULL DEFAULT '' COMMENT '股票代码',
  `stock_name` varchar(20) CHARACTER SET utf8 COLLATE utf8_general_ci DEFAULT '' COMMENT '股票名称',
  `block_label` varchar(10) CHARACTER SET utf8 COLLATE utf8_general_ci DEFAULT NULL COMMENT '所属行业板块标识',
  `block_name` varchar(20) CHARACTER SET utf8 COLLATE utf8_general_ci DEFAULT NULL COMMENT '行业板块名称',
  `business` varchar(300) CHARACTER SET utf8 COLLATE utf8_general_ci DEFAULT NULL COMMENT '主营业务',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`stock_code`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARACTER SET=utf8 COLLATE=utf8_general_ci COMMENT='主营业务表' ROW_FORMAT=COMPACT;

CREATE TABLE `stock_market_index_info` (
  `id` bigint NOT NULL COMMENT '主键 ID，由应用生成',
  `market_code` char(12) CHARACTER SET utf8 COLLATE utf8_general_ci NOT NULL COMMENT '大盘编码',
  `market_name` varchar(20) CHARACTER SET utf8 COLLATE utf8_general_ci DEFAULT NULL COMMENT '指数名称',
  `pre_close_point` decimal(18,2) DEFAULT NULL COMMENT '前收盘点数',
  `open_point` decimal(18,2) DEFAULT NULL COMMENT '开盘点数',
  `cur_point` decimal(18,2) DEFAULT NULL COMMENT '当前点数',
  `min_point` decimal(18,2) DEFAULT NULL COMMENT '最低点数',
  `max_point` decimal(18,2) DEFAULT NULL COMMENT '最高点数',
  `trade_amount` bigint DEFAULT NULL COMMENT '成交量',
  `trade_volume` decimal(18,2) DEFAULT NULL COMMENT '成交金额',
  `cur_time` datetime NOT NULL COMMENT '行情时间',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `unique_id_time` (`cur_time`, `market_code`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARACTER SET=utf8 COLLATE=utf8_general_ci COMMENT='国内大盘数据详情表' ROW_FORMAT=COMPACT;

CREATE TABLE `stock_outer_market_index_info` (
  `id` bigint NOT NULL COMMENT '主键 ID，由应用生成',
  `market_code` char(12) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '大盘编码',
  `market_name` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '大盘名称',
  `cur_point` decimal(10,2) DEFAULT NULL COMMENT '当前点位',
  `updown` decimal(10,2) DEFAULT NULL COMMENT '涨跌值',
  `rose` decimal(10,2) DEFAULT NULL COMMENT '涨跌幅',
  `cur_time` datetime NOT NULL COMMENT '行情时间',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `unique_mcode_date` (`cur_time`, `market_code`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARACTER SET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='外盘详情信息表' ROW_FORMAT=COMPACT;

CREATE TABLE `stock_rt_info` (
  `id` bigint NOT NULL COMMENT '主键 ID，由应用生成',
  `stock_code` char(6) CHARACTER SET utf8 COLLATE utf8_general_ci NOT NULL COMMENT '股票代码',
  `stock_name` varchar(20) CHARACTER SET utf8 COLLATE utf8_general_ci NOT NULL COMMENT '股票名称',
  `pre_close_price` decimal(8,2) DEFAULT NULL COMMENT '前收盘价',
  `open_price` decimal(8,2) DEFAULT NULL COMMENT '开盘价',
  `cur_price` decimal(8,2) NOT NULL COMMENT '当前价格',
  `min_price` decimal(8,2) DEFAULT NULL COMMENT '当日最低价',
  `max_price` decimal(8,2) DEFAULT NULL COMMENT '当日最高价',
  `trade_amount` bigint DEFAULT NULL COMMENT '成交量',
  `trade_volume` decimal(18,2) DEFAULT NULL COMMENT '成交金额',
  `cur_time` datetime NOT NULL COMMENT '行情时间',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `cur_time_idx` (`cur_time`, `stock_code`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARACTER SET=utf8 COLLATE=utf8_general_ci COMMENT='个股详情信息表' ROW_FORMAT=COMPACT;

CREATE TABLE `sys_log` (
  `id` bigint NOT NULL COMMENT '主键 ID，由应用生成',
  `user_id` varchar(64) CHARACTER SET utf8 COLLATE utf8_general_ci DEFAULT NULL COMMENT '旧用户引用',
  `username` varchar(50) CHARACTER SET utf8 COLLATE utf8_general_ci DEFAULT NULL COMMENT '用户名快照',
  `operation` varchar(50) CHARACTER SET utf8 COLLATE utf8_general_ci DEFAULT NULL COMMENT '用户操作',
  `time` int DEFAULT NULL COMMENT '响应时间，毫秒',
  `method` varchar(200) CHARACTER SET utf8 COLLATE utf8_general_ci DEFAULT NULL COMMENT '控制层方法',
  `params` varchar(5000) CHARACTER SET utf8 COLLATE utf8_general_ci DEFAULT NULL COMMENT '请求参数',
  `ip` varchar(64) CHARACTER SET utf8 COLLATE utf8_general_ci DEFAULT NULL COMMENT 'IP 地址',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  PRIMARY KEY (`id`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARACTER SET=utf8 COLLATE=utf8_general_ci COMMENT='系统日志' ROW_FORMAT=COMPACT;

CREATE TABLE `sys_permission` (
  `id` bigint NOT NULL COMMENT '主键 ID，由应用生成',
  `code` varchar(64) CHARACTER SET utf8 COLLATE utf8_general_ci DEFAULT NULL COMMENT '菜单权限编码',
  `title` varchar(300) CHARACTER SET utf8 COLLATE utf8_general_ci DEFAULT NULL COMMENT '菜单权限名称',
  `icon` varchar(60) CHARACTER SET utf8 COLLATE utf8_general_ci DEFAULT '' COMMENT '菜单图标',
  `perms` varchar(500) CHARACTER SET utf8 COLLATE utf8_general_ci DEFAULT NULL COMMENT 'Spring Security 授权标识',
  `url` varchar(100) CHARACTER SET utf8 COLLATE utf8_general_ci DEFAULT NULL COMMENT '访问地址',
  `method` varchar(10) CHARACTER SET utf8 COLLATE utf8_general_ci DEFAULT NULL COMMENT 'HTTP 方法',
  `name` varchar(255) CHARACTER SET utf8 COLLATE utf8_general_ci DEFAULT '' COMMENT '前端路由名称',
  `pid` bigint DEFAULT 0 COMMENT '父级权限 ID',
  `order_num` int DEFAULT 0 COMMENT '排序',
  `type` tinyint unsigned DEFAULT 1 COMMENT '1目录、2菜单、3按钮',
  `status` tinyint DEFAULT 1 COMMENT '1正常、0禁用',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `update_time` datetime DEFAULT NULL COMMENT '更新时间',
  `deleted` tinyint DEFAULT 1 COMMENT '遗留语义：1未删除、0已删除',
  PRIMARY KEY (`id`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARACTER SET=utf8 COLLATE=utf8_general_ci COMMENT='权限表' ROW_FORMAT=COMPACT;

CREATE TABLE `sys_role` (
  `id` bigint NOT NULL COMMENT '主键 ID，由应用生成',
  `name` varchar(255) CHARACTER SET utf8 COLLATE utf8_general_ci DEFAULT NULL COMMENT '角色名称',
  `description` varchar(300) CHARACTER SET utf8 COLLATE utf8_general_ci DEFAULT NULL COMMENT '描述',
  `status` tinyint DEFAULT 1 COMMENT '1正常、0弃用',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `update_time` datetime DEFAULT NULL COMMENT '更新时间',
  `deleted` tinyint DEFAULT 1 COMMENT '遗留语义：1未删除、0已删除',
  PRIMARY KEY (`id`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARACTER SET=utf8 COLLATE=utf8_general_ci COMMENT='角色表' ROW_FORMAT=COMPACT;

CREATE TABLE `sys_role_permission` (
  `id` bigint NOT NULL COMMENT '主键 ID，由应用生成',
  `role_id` bigint DEFAULT NULL COMMENT '角色 ID',
  `permission_id` bigint DEFAULT NULL COMMENT '权限 ID',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  PRIMARY KEY (`id`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARACTER SET=utf8 COLLATE=utf8_general_ci COMMENT='角色权限表' ROW_FORMAT=COMPACT;

CREATE TABLE `sys_user` (
  `id` bigint NOT NULL COMMENT '用户 ID，由应用生成',
  `username` varchar(50) CHARACTER SET utf8 COLLATE utf8_general_ci NOT NULL COMMENT '账号',
  `password` varchar(200) CHARACTER SET utf8 COLLATE utf8_general_ci NOT NULL COMMENT '密码密文',
  `phone` varchar(20) CHARACTER SET utf8 COLLATE utf8_general_ci DEFAULT NULL COMMENT '手机号码',
  `real_name` varchar(60) CHARACTER SET utf8 COLLATE utf8_general_ci DEFAULT NULL COMMENT '真实名称',
  `nick_name` varchar(60) CHARACTER SET utf8 COLLATE utf8_general_ci DEFAULT NULL COMMENT '昵称',
  `email` varchar(50) CHARACTER SET utf8 COLLATE utf8_general_ci DEFAULT NULL COMMENT '邮箱',
  `status` tinyint DEFAULT 1 COMMENT '1正常、2锁定',
  `sex` tinyint DEFAULT 1 COMMENT '1男、2女',
  `deleted` tinyint DEFAULT 1 COMMENT '遗留语义：1未删除、0已删除',
  `create_id` bigint DEFAULT NULL COMMENT '创建人',
  `update_id` bigint DEFAULT NULL COMMENT '更新人',
  `create_where` tinyint DEFAULT 1 COMMENT '1 Web、2 Android、3 iOS',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `update_time` datetime DEFAULT NULL COMMENT '更新时间',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `unique_username` (`username`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARACTER SET=utf8 COLLATE=utf8_general_ci COMMENT='用户表' ROW_FORMAT=COMPACT;

CREATE TABLE `sys_user_role` (
  `id` bigint NOT NULL COMMENT '主键 ID，由应用生成',
  `user_id` bigint DEFAULT NULL COMMENT '用户 ID',
  `role_id` bigint DEFAULT NULL COMMENT '角色 ID',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  PRIMARY KEY (`id`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARACTER SET=utf8 COLLATE=utf8_general_ci COMMENT='用户角色表' ROW_FORMAT=COMPACT;

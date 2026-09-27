-- M3-11 后台资讯治理：播种 ADM-NEWS-01~08 的权限码与角色绑定。
--
-- 与 V9 同一条纪律：**只播种结构与权限码，不建任何账号**（理由见 V9 头注）。
-- 资讯来源与关联审核的表结构在 V4 已就位，本迁移只补 V9 没有覆盖的
-- 契约 §17.1 / §17.2 八个权限标识。
--
-- 说明（承接 V9 的约定，不重复的理由写在 V9）：
-- 1. `deleted` 反向语义（1 = 未删除），所有行显式写 1。
-- 2. `code` 与 `perms` 刻意取不同值：code 是 `admin:news-source:*`，
--    perms 是契约 §17 的 `news:source:*` / `news:relation:*`，鉴权读 COALESCE(perms, code)。
-- 3. id 沿用 V9 的 9_900_000_000_1xx（权限）/ 2xx（绑定）段，取未被占用的 131~138 / 230~237。
-- 4. 挂到 V9 建的 ADMIN 角色（9900000000100）下，后台账号开箱即拥有资讯治理能力。

-- ---------- 资讯来源管理（§17.1 ADM-NEWS-01~04）----------
INSERT INTO `sys_permission`
  (`id`, `code`, `title`, `perms`, `pid`, `order_num`, `type`, `status`, `deleted`, `create_time`, `update_time`)
VALUES
  (9900000000131, 'admin:news-source:list',   '来源列表', 'news:source:list',   9900000000001, 131, 3, 1, 1, NOW(), NOW()),
  (9900000000132, 'admin:news-source:detail', '来源详情', 'news:source:detail', 9900000000001, 132, 3, 1, 1, NOW(), NOW()),
  (9900000000133, 'admin:news-source:create', '创建来源', 'news:source:create', 9900000000001, 133, 3, 1, 1, NOW(), NOW()),
  (9900000000134, 'admin:news-source:update', '修改来源', 'news:source:update', 9900000000001, 134, 3, 1, 1, NOW(), NOW())
ON DUPLICATE KEY UPDATE
  `code` = VALUES(`code`), `title` = VALUES(`title`), `perms` = VALUES(`perms`),
  `pid` = VALUES(`pid`), `order_num` = VALUES(`order_num`), `type` = VALUES(`type`),
  `status` = 1, `deleted` = 1, `update_time` = NOW();

-- ---------- 资讯关联审核（§17.2 ADM-NEWS-05~08）----------
INSERT INTO `sys_permission`
  (`id`, `code`, `title`, `perms`, `pid`, `order_num`, `type`, `status`, `deleted`, `create_time`, `update_time`)
VALUES
  (9900000000135, 'admin:news-relation:list',   '关联列表', 'news:relation:list',   9900000000001, 135, 3, 1, 1, NOW(), NOW()),
  (9900000000136, 'admin:news-relation:review', '关联复核', 'news:relation:review', 9900000000001, 136, 3, 1, 1, NOW(), NOW()),
  (9900000000137, 'admin:news-relation:create', '手工关联', 'news:relation:create', 9900000000001, 137, 3, 1, 1, NOW(), NOW()),
  (9900000000138, 'admin:news-relation:delete', '关联删除', 'news:relation:delete', 9900000000001, 138, 3, 1, 1, NOW(), NOW())
ON DUPLICATE KEY UPDATE
  `code` = VALUES(`code`), `title` = VALUES(`title`), `perms` = VALUES(`perms`),
  `pid` = VALUES(`pid`), `order_num` = VALUES(`order_num`), `type` = VALUES(`type`),
  `status` = 1, `deleted` = 1, `update_time` = NOW();

-- ---------- ADMIN 角色绑定（8 条）----------
INSERT INTO `sys_role_permission` (`id`, `role_id`, `permission_id`, `create_time`)
VALUES
  (9900000000230, 9900000000100, 9900000000131, NOW()),
  (9900000000231, 9900000000100, 9900000000132, NOW()),
  (9900000000232, 9900000000100, 9900000000133, NOW()),
  (9900000000233, 9900000000100, 9900000000134, NOW()),
  (9900000000234, 9900000000100, 9900000000135, NOW()),
  (9900000000235, 9900000000100, 9900000000136, NOW()),
  (9900000000236, 9900000000100, 9900000000137, NOW()),
  (9900000000237, 9900000000100, 9900000000138, NOW())
ON DUPLICATE KEY UPDATE
  `role_id` = VALUES(`role_id`), `permission_id` = VALUES(`permission_id`);

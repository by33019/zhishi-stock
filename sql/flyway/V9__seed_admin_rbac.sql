-- M3-11 后台管理面：播种 RBAC 结构（角色 + 权限 + 绑定）。
--
-- 这里**只建结构与权限码，不建任何账号**：账号属于环境数据，dev/test 由
-- DevelopmentAccountSeeder 建（密码走配置项），生产环境由运维建。把演示账号写进
-- 迁移脚本，等于给每一个克隆下来的生产库都留一个已知密码的高权限入口。
--
-- 说明：
-- 1. `deleted` 是遗留反向语义（1 = 未删除、0 = 已删除），所有行必须显式写 1，
--    否则 V2 加的 active_code / active_perms / active_name 生成列会把它当成"已删除"。
-- 2. 因此 `perms` 必须在未删除行内唯一（uk_sys_permission_perms），
--    `code` 同理（uk_sys_permission_code）——每个权限码只出现一次。
-- 3. `code` 与 `perms` 刻意取不同的值（code 是 `admin:user:list`、perms 是 `sys:user:list`），
--    这样"鉴权读的是 perms 还是 code"在数据上可观测：
--    SysUserMapper 取的是 COALESCE(NULLIF(perms,''), code)，写错一处会立刻表现为 403。
-- 4. id 用 9_900_000_000_1xx / 2xx 段，避开 DevelopmentAccountSeeder 已占用的 001~005。
-- 5. `admin:access` 是**前端**判断"能否显示后台入口"的唯一判据（auth.ts 的 isAdmin）；
--    后端不用它做鉴权，后端逐个端点校验下面那些 `sys:*` / `ops:*` / `ai:ops:*` 码。
--
-- 范围（契约 §16.1 / §16.2-只读 / §18.3 / §19 / §20）：
--   23 个端点共 22 个权限码（ADM-JOB-01 与 ADM-JOB-03 共用 ops:job:list）
--   + 1 条后台菜单权限，共 23 行 sys_permission。

-- ---------- 后台菜单（前端入口判据）----------
INSERT INTO `sys_permission`
  (`id`, `code`, `title`, `perms`, `url`, `name`, `pid`, `order_num`, `type`, `status`, `deleted`, `create_time`, `update_time`)
VALUES
  (9900000000001, 'admin', '系统运营', 'admin:access', '/admin', 'admin', 0, 900, 1, 1, 1, NOW(), NOW())
ON DUPLICATE KEY UPDATE
  `code` = VALUES(`code`), `title` = VALUES(`title`), `perms` = VALUES(`perms`),
  `url` = VALUES(`url`), `name` = VALUES(`name`), `pid` = VALUES(`pid`),
  `order_num` = VALUES(`order_num`), `type` = VALUES(`type`), `status` = 1, `deleted` = 1,
  `update_time` = NOW();

-- ---------- 用户管理 ADM-USR-01~09 ----------
INSERT INTO `sys_permission`
  (`id`, `code`, `title`, `perms`, `pid`, `order_num`, `type`, `status`, `deleted`, `create_time`, `update_time`)
VALUES
  (9900000000101, 'admin:user:list',           '用户列表',   'sys:user:list',           9900000000001, 101, 3, 1, 1, NOW(), NOW()),
  (9900000000102, 'admin:user:detail',         '用户详情',   'sys:user:detail',         9900000000001, 102, 3, 1, 1, NOW(), NOW()),
  (9900000000103, 'admin:user:create',         '创建用户',   'sys:user:create',         9900000000001, 103, 3, 1, 1, NOW(), NOW()),
  (9900000000104, 'admin:user:update',         '修改用户',   'sys:user:update',         9900000000001, 104, 3, 1, 1, NOW(), NOW()),
  (9900000000105, 'admin:user:status',         '用户状态',   'sys:user:status',         9900000000001, 105, 3, 1, 1, NOW(), NOW()),
  (9900000000106, 'admin:user:role',           '用户角色',   'sys:user:role',           9900000000001, 106, 3, 1, 1, NOW(), NOW()),
  (9900000000107, 'admin:user:password-reset', '密码重置',   'sys:user:password-reset', 9900000000001, 107, 3, 1, 1, NOW(), NOW()),
  (9900000000108, 'admin:user:session-revoke', '强制下线',   'sys:user:session-revoke', 9900000000001, 108, 3, 1, 1, NOW(), NOW()),
  (9900000000109, 'admin:user:delete',         '删除用户',   'sys:user:delete',         9900000000001, 109, 3, 1, 1, NOW(), NOW())
ON DUPLICATE KEY UPDATE
  `code` = VALUES(`code`), `title` = VALUES(`title`), `perms` = VALUES(`perms`),
  `pid` = VALUES(`pid`), `order_num` = VALUES(`order_num`), `type` = VALUES(`type`),
  `status` = 1, `deleted` = 1, `update_time` = NOW();

-- ---------- 角色（只读列表，ADM-ROL-01）----------
-- 角色增删改（ADM-ROL-02~06）不在本轮范围内，因此只有 list 这一个码。
INSERT INTO `sys_permission`
  (`id`, `code`, `title`, `perms`, `pid`, `order_num`, `type`, `status`, `deleted`, `create_time`, `update_time`)
VALUES
  (9900000000110, 'admin:role:list', '角色列表', 'sys:role:list', 9900000000001, 110, 3, 1, 1, NOW(), NOW())
ON DUPLICATE KEY UPDATE
  `code` = VALUES(`code`), `title` = VALUES(`title`), `perms` = VALUES(`perms`),
  `pid` = VALUES(`pid`), `order_num` = VALUES(`order_num`), `type` = VALUES(`type`),
  `status` = 1, `deleted` = 1, `update_time` = NOW();

-- ---------- 定时任务 ADM-JOB-01~05 ----------
-- ADM-JOB-01（定义列表）与 ADM-JOB-03（执行分页）共用 ops:job:list：
-- 契约给这两个端点的权限标识本来就是同一个，拆成两个码会让"能看定义但不能看执行"成为
-- 一个无人配置得出来的状态。
INSERT INTO `sys_permission`
  (`id`, `code`, `title`, `perms`, `pid`, `order_num`, `type`, `status`, `deleted`, `create_time`, `update_time`)
VALUES
  (9900000000111, 'admin:job:list',    '任务列表', 'ops:job:list',    9900000000001, 111, 3, 1, 1, NOW(), NOW()),
  (9900000000112, 'admin:job:trigger', '人工触发', 'ops:job:trigger', 9900000000001, 112, 3, 1, 1, NOW(), NOW()),
  (9900000000113, 'admin:job:detail',  '任务详情', 'ops:job:detail',  9900000000001, 113, 3, 1, 1, NOW(), NOW()),
  (9900000000114, 'admin:job:retry',   '任务重试', 'ops:job:retry',   9900000000001, 114, 3, 1, 1, NOW(), NOW())
ON DUPLICATE KEY UPDATE
  `code` = VALUES(`code`), `title` = VALUES(`title`), `perms` = VALUES(`perms`),
  `pid` = VALUES(`pid`), `order_num` = VALUES(`order_num`), `type` = VALUES(`type`),
  `status` = 1, `deleted` = 1, `update_time` = NOW();

-- ---------- AI 运营 ADM-AI-01~06 ----------
INSERT INTO `sys_permission`
  (`id`, `code`, `title`, `perms`, `pid`, `order_num`, `type`, `status`, `deleted`, `create_time`, `update_time`)
VALUES
  (9900000000115, 'admin:ai:overview',    'AI 运营总览', 'ai:ops:overview',    9900000000001, 115, 3, 1, 1, NOW(), NOW()),
  (9900000000116, 'admin:ai:task-list',   'AI 任务列表', 'ai:ops:task-list',   9900000000001, 116, 3, 1, 1, NOW(), NOW()),
  (9900000000117, 'admin:ai:task-detail', 'AI 任务详情', 'ai:ops:task-detail', 9900000000001, 117, 3, 1, 1, NOW(), NOW()),
  (9900000000118, 'admin:ai:task-cancel', 'AI 任务取消', 'ai:ops:task-cancel', 9900000000001, 118, 3, 1, 1, NOW(), NOW()),
  (9900000000119, 'admin:ai:usage',       'AI 用量',     'ai:ops:usage',       9900000000001, 119, 3, 1, 1, NOW(), NOW()),
  (9900000000120, 'admin:ai:feedback',    'AI 反馈统计', 'ai:ops:feedback',    9900000000001, 120, 3, 1, 1, NOW(), NOW())
ON DUPLICATE KEY UPDATE
  `code` = VALUES(`code`), `title` = VALUES(`title`), `perms` = VALUES(`perms`),
  `pid` = VALUES(`pid`), `order_num` = VALUES(`order_num`), `type` = VALUES(`type`),
  `status` = 1, `deleted` = 1, `update_time` = NOW();

-- ---------- 操作日志 LOG-01/02 ----------
INSERT INTO `sys_permission`
  (`id`, `code`, `title`, `perms`, `pid`, `order_num`, `type`, `status`, `deleted`, `create_time`, `update_time`)
VALUES
  (9900000000121, 'admin:log:list',   '日志列表', 'sys:log:list',   9900000000001, 121, 3, 1, 1, NOW(), NOW()),
  (9900000000122, 'admin:log:detail', '日志详情', 'sys:log:detail', 9900000000001, 122, 3, 1, 1, NOW(), NOW())
ON DUPLICATE KEY UPDATE
  `code` = VALUES(`code`), `title` = VALUES(`title`), `perms` = VALUES(`perms`),
  `pid` = VALUES(`pid`), `order_num` = VALUES(`order_num`), `type` = VALUES(`type`),
  `status` = 1, `deleted` = 1, `update_time` = NOW();

-- ---------- ADMIN 角色 ----------
-- 角色名必须是 `stock.admin.super-role-name` 的默认值（ADMIN）：后台的
-- "最后一个超级管理员保护"按角色名判定（见 SuperAdminRule）。改名会让保护失效，
-- 因此这个字面量与配置默认值必须一致。
INSERT INTO `sys_role` (`id`, `name`, `description`, `status`, `deleted`, `create_time`, `update_time`)
VALUES (9900000000100, 'ADMIN', '系统管理员（后台管理面）', 1, 1, NOW(), NOW())
ON DUPLICATE KEY UPDATE
  `name` = VALUES(`name`), `description` = VALUES(`description`),
  `status` = 1, `deleted` = 1, `update_time` = NOW();

-- ---------- 角色权限绑定（23 条：菜单 + 22 个端点权限码）----------
INSERT INTO `sys_role_permission` (`id`, `role_id`, `permission_id`, `create_time`)
VALUES
  (9900000000200, 9900000000100, 9900000000001, NOW()),
  (9900000000201, 9900000000100, 9900000000101, NOW()),
  (9900000000202, 9900000000100, 9900000000102, NOW()),
  (9900000000203, 9900000000100, 9900000000103, NOW()),
  (9900000000204, 9900000000100, 9900000000104, NOW()),
  (9900000000205, 9900000000100, 9900000000105, NOW()),
  (9900000000206, 9900000000100, 9900000000106, NOW()),
  (9900000000207, 9900000000100, 9900000000107, NOW()),
  (9900000000208, 9900000000100, 9900000000108, NOW()),
  (9900000000209, 9900000000100, 9900000000109, NOW()),
  (9900000000210, 9900000000100, 9900000000110, NOW()),
  (9900000000211, 9900000000100, 9900000000111, NOW()),
  (9900000000212, 9900000000100, 9900000000112, NOW()),
  (9900000000213, 9900000000100, 9900000000113, NOW()),
  (9900000000214, 9900000000100, 9900000000114, NOW()),
  (9900000000215, 9900000000100, 9900000000115, NOW()),
  (9900000000216, 9900000000100, 9900000000116, NOW()),
  (9900000000217, 9900000000100, 9900000000117, NOW()),
  (9900000000218, 9900000000100, 9900000000118, NOW()),
  (9900000000219, 9900000000100, 9900000000119, NOW()),
  (9900000000220, 9900000000100, 9900000000120, NOW()),
  (9900000000221, 9900000000100, 9900000000121, NOW()),
  (9900000000222, 9900000000100, 9900000000122, NOW())
ON DUPLICATE KEY UPDATE
  `role_id` = VALUES(`role_id`), `permission_id` = VALUES(`permission_id`);

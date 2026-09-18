-- ============================================================
-- 种子数据:平台级初始数据(架构文档 9.5)
--
-- 为什么必须有这个脚本:sys_menu 是平台统一数据,没有它超管登录后连一个菜单都没有;
-- V1 只负责结构,起不来系统。
--
-- 两条硬性约定:
--   1. 幂等:全部使用 INSERT ... ON DUPLICATE KEY UPDATE(no-op),重复执行不产生重复数据,
--      也不像 INSERT IGNORE 那样把数据错误一起吞掉。
--   2. **固定小整数 ID**:这是全方案唯一不用雪花 ID 的地方,原因是后续版本的脚本要能稳定引用
--      这些 ID(比如某次迁移要把新菜单加进"全量套餐"),雪花 ID 每次生成都不同、引用不了。
--      业务数据一律雪花 ID,不要混用。
--
-- 平台超管初始账号:tenant_code = platform / admin / admin123
--   密码字段是真实可用的 BCrypt(cost 10)哈希,已置 must_change_password = 1,
--   首次登录会被强制改密。**上线前请务必确认这条数据没有被改动过。**
-- ============================================================

SET NAMES utf8mb4;

-- ------------------------------------------------------------
-- 平台租户:超管账号挂在这个租户下。它不参与业务数据的租户隔离判断
-- (能否跨租户看数据取决于 sys_user.is_super,不是租户,见架构文档 4.10)
-- ------------------------------------------------------------
INSERT INTO tenant (id, tenant_code, tenant_name, status, package_id, expire_time, create_by)
VALUES (1, 'platform', '平台', 1, 1, NULL, NULL)
ON DUPLICATE KEY UPDATE tenant_code = tenant_code;

-- 平台租户的根部门:data_scope = 2/3/4 依赖部门层级,没有根部门这些档位会退化成"看不到任何数据"
INSERT INTO sys_dept (id, tenant_id, parent_id, ancestors, dept_name, sort_order, status)
VALUES (1, 1, 0, '', '平台总部', 1, 1)
ON DUPLICATE KEY UPDATE dept_name = dept_name;

-- ------------------------------------------------------------
-- 套餐:全量套餐(供平台租户自己使用,也是新租户的默认选择对象)
-- ------------------------------------------------------------
INSERT INTO sys_package (id, package_name, remark, status)
VALUES (1, '全量套餐', '包含全部非平台专用菜单', 1)
ON DUPLICATE KEY UPDATE package_name = package_name;

-- ------------------------------------------------------------
-- 菜单树
--   is_platform = 1 的是平台管理菜单:不允许进入任何套餐(架构文档 5.2.1),
--   所以普通租户的授权候选集里永远不会出现它们 —— 这是平台级接口的第一层防线(4.10)。
--   perm_code 只挂在 button(menu_type = 3)上,与建表脚本的列注释保持一致。
-- ------------------------------------------------------------
INSERT INTO sys_menu (id, parent_id, menu_name, menu_type, route_path, perm_code, icon, is_platform, sort_order, status) VALUES
    (1,  0, '系统管理', 1, '/system',   NULL,                          'setting', 0, 1, 1),
    (2,  1, '用户管理', 2, 'user',      NULL,                          'user',    0, 1, 1),
    (11, 2, '用户列表', 3, NULL,        'system:user:list',            NULL,      0, 1, 1),
    (12, 2, '用户详情', 3, NULL,        'system:user:query',           NULL,      0, 2, 1),
    (13, 2, '用户新增', 3, NULL,        'system:user:create',          NULL,      0, 3, 1),
    (14, 2, '用户修改', 3, NULL,        'system:user:update',          NULL,      0, 4, 1),
    (15, 2, '用户删除', 3, NULL,        'system:user:delete',          NULL,      0, 5, 1),
    (16, 2, '用户启停', 3, NULL,        'system:user:status',          NULL,      0, 6, 1),
    (17, 2, '重置密码', 3, NULL,        'system:user:reset-password',  NULL,      0, 7, 1),
    (18, 2, '解除锁定', 3, NULL,        'system:user:unlock',          NULL,      0, 8, 1),
    (3,  1, '角色管理', 2, 'role',      NULL,                          'team',    0, 2, 1),
    (21, 3, '角色列表', 3, NULL,        'system:role:list',            NULL,      0, 1, 1),
    (22, 3, '角色新增', 3, NULL,        'system:role:create',          NULL,      0, 2, 1),
    (23, 3, '角色修改', 3, NULL,        'system:role:update',          NULL,      0, 3, 1),
    (24, 3, '角色删除', 3, NULL,        'system:role:delete',          NULL,      0, 4, 1),
    (25, 3, '角色启停', 3, NULL,        'system:role:status',          NULL,      0, 5, 1),
    (26, 3, '角色授权', 3, NULL,        'system:role:grant',           NULL,      0, 6, 1),
    (4,  1, '部门管理', 2, 'dept',      NULL,                          'apartment',0, 3, 1),
    (31, 4, '部门列表', 3, NULL,        'system:dept:list',            NULL,      0, 1, 1),
    (32, 4, '部门新增', 3, NULL,        'system:dept:create',          NULL,      0, 2, 1),
    (33, 4, '部门修改', 3, NULL,        'system:dept:update',          NULL,      0, 3, 1),
    (34, 4, '部门删除', 3, NULL,        'system:dept:delete',          NULL,      0, 4, 1),
    (5,  0, '平台管理', 1, '/platform', NULL,                          'crown',   1, 9, 1),
    (6,  5, '租户管理', 2, 'tenant',    NULL,                          'shop',    1, 1, 1),
    (41, 6, '租户列表', 3, NULL,        'system:tenant:list',          NULL,      1, 1, 1),
    (42, 6, '租户新增', 3, NULL,        'system:tenant:create',        NULL,      1, 2, 1),
    (43, 6, '租户换套餐', 3, NULL,      'system:tenant:package',       NULL,      1, 3, 1),
    (44, 6, '租户启停', 3, NULL,        'system:tenant:status',        NULL,      1, 4, 1),
    (7,  5, '套餐管理', 2, 'package',   NULL,                          'gift',    1, 2, 1),
    (51, 7, '套餐列表', 3, NULL,        'system:package:list',         NULL,      1, 1, 1),
    (52, 7, '套餐新增', 3, NULL,        'system:package:create',       NULL,      1, 2, 1),
    (53, 7, '套餐修改', 3, NULL,        'system:package:update',       NULL,      1, 3, 1),
    (8,  5, '菜单管理', 2, 'menu',      NULL,                          'menu',    1, 3, 1),
    (61, 8, '菜单列表', 3, NULL,        'system:menu:list',            NULL,      1, 1, 1),
    (62, 8, '菜单新增', 3, NULL,        'system:menu:create',          NULL,      1, 2, 1),
    (63, 8, '菜单修改', 3, NULL,        'system:menu:update',          NULL,      1, 3, 1),
    (64, 8, '菜单删除', 3, NULL,        'system:menu:delete',          NULL,      1, 4, 1)
ON DUPLICATE KEY UPDATE menu_name = VALUES(menu_name),
                        parent_id = VALUES(parent_id),
                        menu_type = VALUES(menu_type),
                        route_path = VALUES(route_path),
                        perm_code = VALUES(perm_code),
                        is_platform = VALUES(is_platform),
                        sort_order = VALUES(sort_order),
                        status = VALUES(status);

-- ------------------------------------------------------------
-- 平台管理员角色 + 授予全部菜单(含平台专用菜单)
-- 超管的权限不走角色(StpInterface 对 is_super = 1 短路返回全量,见 4.10),
-- 这里仍然绑定,是为了让"平台租户下的普通运营账号"将来可以按角色授权。
-- ------------------------------------------------------------
INSERT INTO sys_role (id, tenant_id, role_key, role_name, data_scope, is_default, status)
VALUES (1, 1, 'platform_admin', '平台管理员', 5, 1, 1)
ON DUPLICATE KEY UPDATE role_name = role_name;

INSERT INTO sys_role_menu (role_id, menu_id)
SELECT 1, id FROM sys_menu
ON DUPLICATE KEY UPDATE menu_id = menu_id;

-- ------------------------------------------------------------
-- 全量套餐的菜单 = 全部非平台专用菜单。
-- 用 SELECT 而不是硬编码列表:后续版本新增业务菜单时,只要 is_platform = 0 就会自动落到全量套餐里。
-- ------------------------------------------------------------
INSERT INTO sys_package_menu (package_id, menu_id)
SELECT 1, id FROM sys_menu WHERE is_platform = 0
ON DUPLICATE KEY UPDATE menu_id = menu_id;

-- ------------------------------------------------------------
-- 平台超管账号
-- 密码:admin123(BCrypt cost 10)。上线前必须确认此值,并在首次登录后立即修改。
-- ------------------------------------------------------------
INSERT INTO sys_user (id, tenant_id, dept_id, username, password, nickname, status, is_super, must_change_password)
VALUES (1, 1, 1, 'admin', '$2a$10$olnYwrHwQDwFgYWeK7Q.tOrN0WviMndlIg3rN5LRZLk0jm2DlgIj6', '平台超管', 1, 1, 1)
ON DUPLICATE KEY UPDATE is_super = VALUES(is_super),
                        must_change_password = VALUES(must_change_password);

INSERT INTO sys_user_role (user_id, role_id) VALUES (1, 1)
ON DUPLICATE KEY UPDATE role_id = role_id;

-- ------------------------------------------------------------
-- 基础字典
-- ------------------------------------------------------------
INSERT INTO sys_dict_type (id, dict_type, dict_name) VALUES
    (1, 'sys_common_status', '通用状态'),
    (2, 'sys_menu_type', '菜单类型')
ON DUPLICATE KEY UPDATE dict_name = VALUES(dict_name);

INSERT INTO sys_dict_data (id, dict_type, dict_label, dict_value, sort_order) VALUES
    (1, 'sys_common_status', '正常', '1', 1),
    (2, 'sys_common_status', '停用', '0', 2),
    (3, 'sys_menu_type', '目录', '1', 1),
    (4, 'sys_menu_type', '页面', '2', 2),
    (5, 'sys_menu_type', '按钮', '3', 3)
ON DUPLICATE KEY UPDATE dict_label = VALUES(dict_label),
                        dict_value = VALUES(dict_value),
                        sort_order = VALUES(sort_order);

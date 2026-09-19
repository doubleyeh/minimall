-- ============================================================
-- 字典管理菜单(架构文档 5.1、9.5)
--
-- 为什么单独一个版本而不是改 V2:9.5 规定"已发布脚本不可变" —— V2 已经跑过的环境不会重跑它,
-- 往里加菜单只会让"新环境有菜单、老环境没菜单",版本之间产生分歧。新增一律新开版本。
--
-- 为什么挂在"平台管理"(id = 5)下:sys_dict_type/sys_dict_data 是平台级表(4.6.1),
-- 全局共用、不分租户,所以维护入口也必须是平台专用菜单(is_platform = 1)。
-- 这样处理的好处是租户侧不用额外写校验:5.2.1 的"授权不得超出套餐范围"会让租户角色
-- 永远拿不到 system:dict:* —— 平台菜单(含本菜单)不在任何套餐里(见 V2 的 sys_package_menu 语句)。
-- ============================================================

INSERT INTO sys_menu (id, parent_id, menu_name, menu_type, route_path, perm_code, icon, is_platform, sort_order, status) VALUES
    (9,  5, '字典管理', 2, 'dict', NULL,                'book', 1, 4, 1),
    (91, 9, '字典列表', 3, NULL,   'system:dict:list',   NULL,   1, 1, 1),
    (92, 9, '字典新增', 3, NULL,   'system:dict:create', NULL,   1, 2, 1),
    (93, 9, '字典修改', 3, NULL,   'system:dict:update', NULL,   1, 3, 1),
    (94, 9, '字典删除', 3, NULL,   'system:dict:delete', NULL,   1, 4, 1)
ON DUPLICATE KEY UPDATE menu_name = VALUES(menu_name),
                        parent_id = VALUES(parent_id),
                        menu_type = VALUES(menu_type),
                        route_path = VALUES(route_path),
                        perm_code = VALUES(perm_code),
                        is_platform = VALUES(is_platform),
                        sort_order = VALUES(sort_order),
                        status = VALUES(status);

-- 平台管理员角色补齐新菜单。
-- 超管本身不走角色(StpInterface 对 is_super = 1 短路返回全量,见 4.10),这条是为了
-- "平台租户下的普通运营账号"将来按角色授权时行为一致 —— V2 里那句 SELECT 只覆盖了当时已有的菜单。
INSERT INTO sys_role_menu (role_id, menu_id)
SELECT 1, id FROM sys_menu WHERE parent_id = 9 OR id = 9
ON DUPLICATE KEY UPDATE menu_id = menu_id;

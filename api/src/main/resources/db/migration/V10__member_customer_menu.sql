-- ============================================================
-- 客户管理菜单(商城设计文档 3.11)
--
-- 积分此前只在客户端可见:运营看不到某个客户的积分与成长值,客诉时也无法人工补分。
-- 本次补上客户列表、客户详情与积分调整三个权限点。
--
-- 这是 V2 之后**第一个新增的商城菜单**(此前新增的都是平台菜单,只需授权平台管理员角色),
-- 所以套餐与租户管理员角色的授权都要一起补 —— 漏了的话租户管理员拿不到权限码。
-- ============================================================

INSERT INTO sys_menu (id, parent_id, menu_name, menu_type, route_path, perm_code, icon, is_platform, sort_order, status) VALUES
    (210, 200, '客户管理', 2, 'customer', NULL, 'people', 0, 10, 1),
    (2101, 210, '客户列表', 3, NULL, 'mall:customer:list', NULL, 0, 1, 1),
    (2102, 210, '客户详情', 3, NULL, 'mall:customer:detail', NULL, 0, 2, 1),
    (2103, 210, '积分调整', 3, NULL, 'mall:customer:adjust', NULL, 0, 3, 1)
ON DUPLICATE KEY UPDATE menu_name = VALUES(menu_name),
                        parent_id = VALUES(parent_id),
                        menu_type = VALUES(menu_type),
                        route_path = VALUES(route_path),
                        perm_code = VALUES(perm_code),
                        icon = VALUES(icon),
                        is_platform = VALUES(is_platform),
                        sort_order = VALUES(sort_order),
                        status = VALUES(status);

-- 商城菜单是非平台菜单(is_platform = 0),要落进"全量套餐",否则租户拿不到商城权限码。
-- 与 V2 里那两句同理:新菜单不在 V2 那次 SELECT 的结果里,必须显式再跑一次。
INSERT INTO sys_package_menu (package_id, menu_id)
SELECT 1, id FROM sys_menu WHERE is_platform = 0
ON DUPLICATE KEY UPDATE menu_id = menu_id;

-- 平台管理员角色补齐商城菜单,与 V1 的授权状态保持一致。
-- 超管本身不走角色(StpInterface 对 is_super = 1 短路返回全量,见架构文档 4.10)。
INSERT INTO sys_role_menu (role_id, menu_id)
SELECT 1, id FROM sys_menu WHERE is_platform = 0
ON DUPLICATE KEY UPDATE menu_id = menu_id;

-- ============================================================
-- 操作日志查询菜单(平台管理 id = 5 下)
--
-- 背景:操作日志此前只写不读 —— 表、实体、异步写入器都在,但没有查询接口与页面,
-- 等于审计留了痕却没人能查(前端文档 6.5 一直规划着这个页面)。
--
-- 只授一个查询权限码:日志不允许改与删(架构文档 7.2),所以没有新增/修改/删除按钮。
-- is_platform = 1 的菜单不进任何套餐(5.2.1),所以租户角色永远拿不到这个权限码。
--
-- 菜单 id 取 19/191:sys_menu 的 id 是手工分配的固定小整数,1-18、21-26、31-34、41-44、51-53、
-- 61-64、91-94、101-102 都已被占用。**挑 id 前必须先查一遍现有种子** —— 这里写重了会导致
-- ON DUPLICATE KEY UPDATE 把别的菜单覆盖掉(权限码随之消失,表现是一批接口 403)。
-- ============================================================

INSERT INTO sys_menu (id, parent_id, menu_name, menu_type, route_path, perm_code, icon, is_platform, sort_order, status) VALUES
    (19,  5, '操作日志', 2, 'oper-log', NULL,                    'list', 1, 6, 1),
    (191, 19, '日志查询', 3, NULL,      'system:operlog:list',   NULL,   1, 1, 1)
ON DUPLICATE KEY UPDATE menu_name = VALUES(menu_name),
                        parent_id = VALUES(parent_id),
                        menu_type = VALUES(menu_type),
                        route_path = VALUES(route_path),
                        perm_code = VALUES(perm_code),
                        icon = VALUES(icon),
                        is_platform = VALUES(is_platform),
                        sort_order = VALUES(sort_order),
                        status = VALUES(status);

-- 平台管理员角色补齐新菜单(超管本身不走角色,这里是给"平台租户下的普通运营账号"用)
INSERT INTO sys_role_menu (role_id, menu_id)
SELECT 1, id FROM sys_menu WHERE id IN (19, 191)
ON DUPLICATE KEY UPDATE menu_id = menu_id;

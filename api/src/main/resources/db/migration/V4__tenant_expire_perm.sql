-- ============================================================
-- 租户"改有效期"的权限码(平台管理 → 租户管理 id = 6 下)
--
-- 背景:有效期此前只在"新建租户"时能填,建完就再没入口,但它是真的会被校验的
-- (TenantSnapshot.usable 判 expireTime,租户过滤器每个请求都看),所以缺的是入口而不是能力。
--
-- 为什么单独一个权限码而不是复用 system:tenant:status:与"换套餐/启停"保持同一粒度 ——
-- 能启停不等于能改有效期(改成一个已过去的时间等价于立刻禁用)。
-- ============================================================

INSERT INTO sys_menu (id, parent_id, menu_name, menu_type, route_path, perm_code, icon, is_platform, sort_order, status) VALUES
    (45, 6, '租户改有效期', 3, NULL, 'system:tenant:expire', NULL, 1, 5, 1) AS new
ON DUPLICATE KEY UPDATE menu_name = new.menu_name,
                        parent_id = new.parent_id,
                        menu_type = new.menu_type,
                        route_path = new.route_path,
                        perm_code = new.perm_code,
                        is_platform = new.is_platform,
                        sort_order = new.sort_order,
                        status = new.status;

INSERT INTO sys_role_menu (role_id, menu_id)
SELECT 1, id FROM sys_menu WHERE id = 45
ON DUPLICATE KEY UPDATE menu_id = menu_id;

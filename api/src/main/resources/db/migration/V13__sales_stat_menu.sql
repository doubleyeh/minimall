-- ============================================================
-- 销售统计(商城设计文档 3.4)
--
-- 管理端此前只能一页页翻订单,没有任何经营数字:这个月卖了多少、哪个商品最好卖,
-- 都要人工数。本脚本补页面菜单与权限点(统计口径在代码里,没有需要运营调的阈值)。
--
-- 两个口径要分清,页面上的数字才对得上:
--   · 销售额按"下单时间在区间内、且已支付(pay_time 非空)"的订单实付额 —— 取消/退款过的也算,
--     因为钱确实收过,退款单独在退款额里减
--   · 退款额按"退款成功时间在区间内"统计 —— 与销售额不是同一批订单,只用于看量级
-- ============================================================

-- 商城菜单是非平台菜单(is_platform = 0),要落进"全量套餐",否则租户拿不到权限码(与 V10 同理)
INSERT INTO sys_menu (id, parent_id, menu_name, menu_type, route_path, perm_code, icon, is_platform, sort_order, status) VALUES
    (212,  200, '销售统计', 2, 'sales-stat', NULL,             'chart', 0, 11, 1),
    (2121, 212, '统计查询', 3, NULL,         'mall:stat:list', NULL,    0, 1, 1)
ON DUPLICATE KEY UPDATE menu_name = VALUES(menu_name),
                        parent_id = VALUES(parent_id),
                        menu_type = VALUES(menu_type),
                        route_path = VALUES(route_path),
                        perm_code = VALUES(perm_code),
                        icon = VALUES(icon),
                        is_platform = VALUES(is_platform),
                        sort_order = VALUES(sort_order),
                        status = VALUES(status);

INSERT INTO sys_package_menu (package_id, menu_id)
SELECT 1, id FROM sys_menu WHERE is_platform = 0
ON DUPLICATE KEY UPDATE menu_id = menu_id;

INSERT INTO sys_role_menu (role_id, menu_id)
SELECT 1, id FROM sys_menu WHERE is_platform = 0
ON DUPLICATE KEY UPDATE menu_id = menu_id;

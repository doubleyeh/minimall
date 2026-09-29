-- ============================================================
-- 库存预警(商城设计文档 3.2)
--
-- 商品页只显示汇总库存,运营看不出"哪个规格快没了",低库存只能靠人一个个翻商品列表。
-- 本脚本补三样:阈值配置(字典)、页面菜单、权限点。
--
-- 阈值走字典而不是写死在代码里:与 order_pay_timeout_minutes 那几个同口径,运营改完即时生效。
-- 判据是**可售库存**(stock - locked_stock):只看 stock 会把"挂着一堆待付款"的规格漏掉。
-- ============================================================

INSERT INTO sys_dict_type (id, dict_type, dict_name) VALUES
    (106, 'stock_warn_threshold', '库存预警阈值(件)')
ON DUPLICATE KEY UPDATE dict_name = VALUES(dict_name);

INSERT INTO sys_dict_data (id, dict_type, dict_label, dict_value, sort_order) VALUES
    (108, 'stock_warn_threshold', '可售库存不高于该值即预警', '5', 1)
ON DUPLICATE KEY UPDATE dict_label = VALUES(dict_label),
                        dict_value = VALUES(dict_value),
                        sort_order = VALUES(sort_order);

-- 商城菜单是非平台菜单(is_platform = 0),要落进"全量套餐",否则租户拿不到权限码(与 V10 同理)
INSERT INTO sys_menu (id, parent_id, menu_name, menu_type, route_path, perm_code, icon, is_platform, sort_order, status) VALUES
    (211,  200, '库存预警', 2, 'stock-warn', NULL,              'alert', 0, 2, 1),
    (2111, 211, '预警列表', 3, NULL,         'mall:stock:list', NULL,    0, 1, 1)
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

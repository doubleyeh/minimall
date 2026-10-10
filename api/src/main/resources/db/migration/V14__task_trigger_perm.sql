-- ============================================================
-- 定时任务「立即执行」(架构文档 6.2)
--
-- 用途:运维等不到下一个 cron 点(比如刚修完数据想立刻对账),要从页面手动跑一次。
--
-- 只有**幂等、方向确定**的任务会注册成可手动触发(关闭超时订单、自动确认收货、
-- 售后超时、退款重试、对账、优惠券过期、积分过期、等级重算、套餐到点生效)。
-- 租户到期处理、注销数据清理、日志归档、任务历史清理**不注册** —— 不可逆或一次
-- 影响一批租户,不该有一个 HTTP 入口,不靠前端隐藏按钮。
-- ============================================================

-- 按钮菜单,平台专用:任务一次覆盖所有租户,只有平台运维该有
INSERT INTO sys_menu (id, parent_id, menu_name, menu_type, route_path, perm_code, icon, is_platform, sort_order, status) VALUES
    (193, 20, '立即执行', 3, NULL, 'system:tasklog:trigger', NULL, 1, 2, 1) AS new
ON DUPLICATE KEY UPDATE menu_name = new.menu_name,
                        parent_id = new.parent_id,
                        menu_type = new.menu_type,
                        route_path = new.route_path,
                        perm_code = new.perm_code,
                        icon = new.icon,
                        is_platform = new.is_platform,
                        sort_order = new.sort_order,
                        status = new.status;

-- 平台管理员角色补齐(平台菜单不进套餐,只需角色授权,与 V11 同理)
INSERT INTO sys_role_menu (role_id, menu_id)
SELECT 1, id FROM sys_menu WHERE id IN (20, 193)
ON DUPLICATE KEY UPDATE menu_id = menu_id;

-- ============================================================
-- 租户注销与数据清理(架构文档 4.11)
--
-- 需求:租户注销后保留 3 个月再物理删除,期间可以取消注销;删除前要能把该租户的数据导出去存档。
--
-- 为什么"注销"不新增一个 status 值(比如 2),而是在禁用(status=0)的基础上记一个清理时间:
-- 状态机的每个取值都要被所有既有判断照顾到(usable()、过滤器、前端显示、各种 status=1 的查询),
-- 加一个值等于把它们全部过一遍;而"注销 = 禁用 + 到点清理"用两列就表达清楚了 ——
-- 租户过滤与登录拦截继续按 status=0 生效,不需要动任何既有逻辑。
-- ============================================================

ALTER TABLE tenant
    ADD COLUMN purge_at DATETIME NULL COMMENT '注销后的数据清理时间,到点由任务物理删除该租户全部数据' AFTER expire_time;

-- 注销的权限码。比"改有效期"更重的操作,单独一个码:能启停不等于能注销
INSERT INTO sys_menu (id, parent_id, menu_name, menu_type, route_path, perm_code, icon, is_platform, sort_order, status) VALUES
    (46, 6, '租户注销', 3, NULL, 'system:tenant:close', NULL, 1, 6, 1)
ON DUPLICATE KEY UPDATE menu_name = VALUES(menu_name),
                        parent_id = VALUES(parent_id),
                        menu_type = VALUES(menu_type),
                        route_path = VALUES(route_path),
                        perm_code = VALUES(perm_code),
                        is_platform = VALUES(is_platform),
                        sort_order = VALUES(sort_order),
                        status = VALUES(status);

INSERT INTO sys_role_menu (role_id, menu_id)
SELECT 1, id FROM sys_menu WHERE id = 46
ON DUPLICATE KEY UPDATE menu_id = menu_id;

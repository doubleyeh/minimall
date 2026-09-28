-- ============================================================
-- 定时任务执行历史(架构文档 6.2)
--
-- 7 个定时任务此前只有日志:任务挂了、或者某个任务从某个时刻起不再执行,
-- **没有任何人会知道** —— 这正是把"互斥"做完之后剩下的那半件事。
--
-- 埋点在 ScheduledTaskLock(所有任务共用的咽喉),所以任务本身一行都不用改。
-- 表是平台级的(不带 tenant_id、不过滤器):一次任务执行覆盖所有租户,
-- 而这个页面是平台运维看的。
--
-- 只记成功与失败,**不记"未抢到锁"**:多实例部署时每个周期都会产生
-- (实例数-1) 条跳过记录,一分钟一次的任务光这一项就是上千行/天,而它带来的信息量几乎为零。
-- "任务停了"靠**没有新行**就能看出来(列表里的最后执行时间),不需要跳过记录。
-- ============================================================

CREATE TABLE sys_task_run_log (
    id          BIGINT        PRIMARY KEY COMMENT '雪花ID,应用层生成',
    task_name   VARCHAR(64)   NOT NULL COMMENT '任务名,与 Redis 锁的键一致',
    status      INT           NOT NULL COMMENT '1-成功 2-失败',
    start_time  DATETIME      NOT NULL,
    end_time    DATETIME      NOT NULL,
    duration_ms BIGINT        NOT NULL,
    error_msg   VARCHAR(1000) NULL COMMENT '失败时的异常摘要',
    create_time DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    create_by   BIGINT        NULL,
    update_by   BIGINT        NULL,
    KEY idx_task_start (task_name, start_time),
    KEY idx_start (start_time)
) COMMENT '定时任务执行历史;保留期见 minimall.task-log.retain-days';

-- 菜单:平台专用(与"操作日志"同一层),不进任何套餐 —— 租户管理员看不到别人的任务执行情况
INSERT INTO sys_menu (id, parent_id, menu_name, menu_type, route_path, perm_code, icon, is_platform, sort_order, status) VALUES
    (20,  5, '定时任务', 2, 'task-log', NULL,                   'time', 1, 7, 1),
    (192, 20, '任务日志', 3, NULL,      'system:tasklog:list',  NULL,   1, 1, 1)
ON DUPLICATE KEY UPDATE menu_name = VALUES(menu_name),
                        parent_id = VALUES(parent_id),
                        menu_type = VALUES(menu_type),
                        route_path = VALUES(route_path),
                        perm_code = VALUES(perm_code),
                        icon = VALUES(icon),
                        is_platform = VALUES(is_platform),
                        sort_order = VALUES(sort_order),
                        status = VALUES(status);

-- 平台管理员角色补齐(平台菜单不进套餐,只需角色授权,与 V7 同理)
INSERT INTO sys_role_menu (role_id, menu_id)
SELECT 1, id FROM sys_menu WHERE id IN (20, 192)
ON DUPLICATE KEY UPDATE menu_id = menu_id;

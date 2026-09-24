-- ============================================================
-- 租户到期提醒的阈值配置(平台级字典)
--
-- 背景:租户到期此前只被"用户请求时被动校验",库里状态一直是"正常" ——
-- 补上到期任务后,需要知道"提前几天提醒"。
--
-- 用字典而不是写死在代码里:与商城侧那几个任务阈值(order_pay_timeout_minutes、
-- order_auto_receive_days、after_sale_timeout)保持同一套做法,运营改完即时生效,
-- 不用改代码重新发版。取不到时任务回退到默认值 7 天。
-- ============================================================

INSERT INTO sys_dict_type (id, dict_type, dict_name) VALUES
    (3, 'tenant_expire_notice_days', '租户到期提醒天数')
ON DUPLICATE KEY UPDATE dict_name = VALUES(dict_name);

INSERT INTO sys_dict_data (id, dict_type, dict_label, dict_value, sort_order) VALUES
    (6, 'tenant_expire_notice_days', '提前天数', '7', 1)
ON DUPLICATE KEY UPDATE dict_label = VALUES(dict_label),
                        dict_value = VALUES(dict_value),
                        sort_order = VALUES(sort_order);

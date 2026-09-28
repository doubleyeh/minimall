-- ============================================================
-- 会员积分与成长值(商城设计文档 3.4,开放项 2)
--
-- 此前只有 mall_customer.points / growth_value 两个裸字段,没有任何变动逻辑。
-- 本脚本补上账本:积分按批次记账以便 FIFO 过期,成长值单独立账以便滚动窗口求和。
--
-- 积分与成长值**分账**,不复用同一张表:
--   * 积分可消耗、可过期 —— 抵现与过期清零都会减少积分
--   * 成长值只受"退款扣回"与"滚动窗口"影响 —— 抵现、过期都不改变它
-- 混在一张表里的话,成长值的滚动求和会被消耗行污染。
-- ============================================================

-- 积分批次账。可用积分 = Σ(remain_points > 0 且未过期) 的批次。
-- 单独立表而不是给 mall_points_log 加列:流水是"发生了什么"(不可变事件),
-- 批次是"还剩什么"(可变状态)。混在一起后,统计类查询(如"总共获得过多少积分")
-- 极易误用 remain_points,而且过期任务要去 UPDATE 一张叫"流水"的表。
CREATE TABLE mall_points_batch (
    id              BIGINT PRIMARY KEY COMMENT '雪花ID,应用层生成',
    tenant_id       BIGINT       NOT NULL,
    customer_id     BIGINT       NOT NULL,
    source_log_id   BIGINT       NULL COMMENT '来源发放流水ID,便于追溯',
    total_points    INT          NOT NULL COMMENT '本批次发放总量,不可变',
    remain_points   INT          NOT NULL COMMENT '本批次剩余可用,消耗/退回/过期时增减',
    expire_time     DATETIME     NOT NULL COMMENT '过期时间=发放时间+有效期(字典 points_expire_months)',
    create_time     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    create_by       BIGINT       NULL,
    update_by       BIGINT       NULL,
    KEY idx_tenant_customer_expire (tenant_id, customer_id, expire_time)
) COMMENT '积分批次账,可用积分=Σ(remain_points>0 且未过期);消耗按 expire_time asc,id asc';

-- 订单抵现占用了哪些批次。订单关闭时要按这张表把积分退回**原批次**(保持原有效期),
-- 只记一条负流水无法知道积分是从哪几个批次扣的。退回后删行,重复取消不会二次退回。
CREATE TABLE mall_points_use (
    id              BIGINT PRIMARY KEY COMMENT '雪花ID,应用层生成',
    tenant_id       BIGINT       NOT NULL,
    order_id        BIGINT       NOT NULL,
    batch_id        BIGINT       NOT NULL,
    points          INT          NOT NULL COMMENT '本订单从该批次消耗的积分数',
    create_time     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    create_by       BIGINT       NULL,
    update_by       BIGINT       NULL,
    KEY idx_tenant_order (tenant_id, order_id)
) COMMENT '订单抵现占用的积分批次明细,订单关闭时按此退回原批次';

-- 成长值流水。近 N 个月(字典 growth_roll_months)求和即当前成长值。
CREATE TABLE mall_growth_log (
    id              BIGINT PRIMARY KEY COMMENT '雪花ID,应用层生成',
    tenant_id       BIGINT       NOT NULL,
    customer_id     BIGINT       NOT NULL,
    change_growth   INT          NOT NULL COMMENT '变动数量,正数为增加负数为减少',
    biz_type        INT          NOT NULL COMMENT '1-确认收货发放 3-退款扣回 4-管理端手动调整',
    biz_id          BIGINT       NULL COMMENT '关联订单ID',
    biz_ref_id      BIGINT       NULL COMMENT '关联售后单ID,退款扣回幂等用',
    remark          VARCHAR(255) NULL,
    create_time     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    create_by       BIGINT       NULL,
    update_by       BIGINT       NULL,
    KEY idx_tenant_customer_time (tenant_id, customer_id, create_time)
) COMMENT '成长值流水,滚动窗口内求和即当前成长值';

-- 两列的口径变了:points 现在是批次账的汇总,growth_value 从"累计不清零"改成滚动值。
ALTER TABLE mall_customer
    MODIFY COLUMN points       INT NOT NULL DEFAULT 0 COMMENT '当前可用积分,必须等于该客户所有未过期批次的剩余之和;变动必须同时写 mall_points_log',
    MODIFY COLUMN growth_value INT NOT NULL DEFAULT 0 COMMENT '近 N 个月滚动成长值(字典 growth_roll_months),可增可减;来源是 mall_growth_log';

-- biz_type 增加 6(订单关闭退回),其余取值语义不变;并补 biz_ref_id 供退款扣回幂等:
-- 扣回既要能按订单汇总(部分退款累计不超过发放值,biz_id=订单),
-- 又要能按售后单判重(biz_ref_id=售后单),两个维度缺一不可。
ALTER TABLE mall_points_log
    MODIFY COLUMN biz_type INT NOT NULL COMMENT '1-确认收货发放 2-抵现消耗 3-退款扣回 4-管理端手动调整 5-过期清零 6-订单关闭退回',
    ADD COLUMN biz_ref_id BIGINT NULL COMMENT '关联售后单ID,仅退款扣回有值' AFTER biz_id;

-- ============================================================
-- 时间窗口配置。走字典而不是写死:与 order_pay_timeout_minutes、
-- after_sale_timeout 保持同一套做法,运营改完即时生效。
-- 比例与抵扣上限(100 积分=1 元、商品金额 50%)是已定的业务口径,不做成配置 ——
-- 可配等于允许把口径改到对不上账。将来确要开放时再提升为字典。
-- ============================================================
INSERT INTO sys_dict_type (id, dict_type, dict_name) VALUES
    (104, 'points_expire_months', '积分批次有效期(月)'),
    (105, 'growth_roll_months', '成长值滚动窗口(月)')
ON DUPLICATE KEY UPDATE dict_name = VALUES(dict_name);

INSERT INTO sys_dict_data (id, dict_type, dict_label, dict_value, sort_order) VALUES
    (106, 'points_expire_months', '月数', '12', 1),
    (107, 'growth_roll_months', '月数', '12', 1)
ON DUPLICATE KEY UPDATE dict_label = VALUES(dict_label),
                        dict_value = VALUES(dict_value),
                        sort_order = VALUES(sort_order);

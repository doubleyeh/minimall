-- ============================================================
-- 订单落库积分抵现(商城设计文档 3.11)
--
-- 订单上要留两个"当时的值":本单用了多少积分、抵了多少钱。
-- 不每次重算的理由与其他金额列一样 —— 退款扣回、售后对账、订单详情都要以它为准,
-- 而积分规则(100:1、上限 50%)将来可能变。
-- ============================================================

ALTER TABLE mall_order
    ADD COLUMN points_used            INT           NOT NULL DEFAULT 0 COMMENT '本单积分抵现消耗的积分数' AFTER pay_amount,
    ADD COLUMN points_discount_amount DECIMAL(10,2) NOT NULL DEFAULT 0 COMMENT '积分抵现金额,上限为商品金额的50%,运费不可抵' AFTER points_used;

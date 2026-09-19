-- ============================================================
-- 微信小商城 - 业务建表脚本
-- 依赖 rbac_tenant_schema.sql 中的 tenant 表(tenant_id 外键关系不建物理约束,与主体建表脚本风格一致)
-- 所有表均带 tenant_id,继承租户过滤机制;不复用 sys_user,客户单独建表
-- 建议作为 V2__mall_init.sql 接在 V1 之后
-- ============================================================

-- ============================================================
-- 客户
-- ============================================================
CREATE TABLE mall_customer (
    id              BIGINT PRIMARY KEY COMMENT '雪花ID,应用层生成',
    tenant_id       BIGINT       NOT NULL,
    openid          VARCHAR(64)  NOT NULL COMMENT '微信小程序openid',
    unionid         VARCHAR(64)  NULL COMMENT '微信开放平台unionid,同一微信主体下多端(公众号/APP)打通时使用',
    nickname        VARCHAR(64)  NULL,
    avatar_url      VARCHAR(255) NULL,
    phone           VARCHAR(20)  NULL,
    gender          TINYINT      NULL COMMENT '0-未知 1-男 2-女',
    status          TINYINT      NOT NULL DEFAULT 1 COMMENT '0-禁用 1-正常',
    member_level_id BIGINT       NULL COMMENT '当前会员等级,为空表示默认等级(不建默认等级记录,应用层兜底展示为"普通会员")',
    points          INT          NOT NULL DEFAULT 0 COMMENT '当前可用积分,变动必须同时写 mall_points_log,不允许绕过流水直接改这个字段',
    growth_value    INT          NOT NULL DEFAULT 0 COMMENT '成长值,用于等级晋升判断,累计不清零(区别于积分可消耗)',
    register_time   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_login_time DATETIME     NULL,
    create_time     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_tenant_openid (tenant_id, openid),
    KEY idx_tenant_unionid (tenant_id, unionid)
) COMMENT '商城客户表,独立于sys_user体系';

CREATE TABLE mall_customer_address (
    id              BIGINT PRIMARY KEY COMMENT '雪花ID,应用层生成',
    tenant_id       BIGINT       NOT NULL,
    customer_id     BIGINT       NOT NULL,
    receiver_name   VARCHAR(32)  NOT NULL,
    receiver_phone  VARCHAR(20)  NOT NULL,
    province        VARCHAR(32)  NOT NULL,
    city            VARCHAR(32)  NOT NULL,
    district        VARCHAR(32)  NOT NULL,
    detail_address  VARCHAR(255) NOT NULL,
    is_default      TINYINT      NOT NULL DEFAULT 0 COMMENT '1-默认地址,同一客户至多一条为1,应用层保证',
    create_time     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    KEY idx_customer (customer_id)
) COMMENT '客户收货地址';

-- ============================================================
-- 会员等级 / 积分
-- ============================================================
CREATE TABLE mall_member_level (
    id              BIGINT PRIMARY KEY COMMENT '雪花ID,应用层生成',
    tenant_id       BIGINT       NOT NULL,
    level_name      VARCHAR(32)  NOT NULL,
    level_sort      INT          NOT NULL DEFAULT 0 COMMENT '等级顺序,数字越大等级越高',
    growth_threshold INT         NOT NULL COMMENT '达到该成长值自动晋升到此等级',
    discount_rate   DECIMAL(3,2) NULL COMMENT '等级折扣率,如0.95表示9.5折;字段先建,晋升/折扣计算逻辑后续实现',
    status          TINYINT      NOT NULL DEFAULT 1,
    create_time     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    KEY idx_tenant (tenant_id)
) COMMENT '会员等级定义,本期只建字段,晋升/权益逻辑不在本期实现范围';

CREATE TABLE mall_points_log (
    id              BIGINT PRIMARY KEY COMMENT '雪花ID,应用层生成',
    tenant_id       BIGINT       NOT NULL,
    customer_id     BIGINT       NOT NULL,
    change_points   INT          NOT NULL COMMENT '变动数量,正数为增加负数为减少',
    balance_points  INT          NOT NULL COMMENT '变动后余额,便于对账,不用每次汇总流水计算',
    biz_type        TINYINT      NOT NULL COMMENT '1-下单获得 2-兑换消耗 3-退款扣回 4-管理端手动调整 5-过期清零',
    biz_id          BIGINT       NULL COMMENT '关联业务ID,如订单ID,管理端手动调整时为空',
    remark          VARCHAR(255) NULL,
    create_time     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY idx_tenant_customer (tenant_id, customer_id)
) COMMENT '积分变动流水,customer.points字段的每次变化都必须对应一条这里的记录';

-- ============================================================
-- 商品分类 / 商品 / 多规格SKU
-- ============================================================
CREATE TABLE mall_goods_category (
    id              BIGINT PRIMARY KEY COMMENT '雪花ID,应用层生成',
    tenant_id       BIGINT       NOT NULL,
    parent_id       BIGINT       NOT NULL DEFAULT 0,
    category_name   VARCHAR(64)  NOT NULL,
    icon            VARCHAR(255) NULL,
    sort_order      INT          NOT NULL DEFAULT 0,
    status          TINYINT      NOT NULL DEFAULT 1,
    create_time     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    KEY idx_tenant_parent (tenant_id, parent_id)
) COMMENT '商品分类,支持两级(一级/二级),不做深层级树';

CREATE TABLE mall_goods (
    id              BIGINT PRIMARY KEY COMMENT '雪花ID,应用层生成',
    tenant_id       BIGINT       NOT NULL,
    category_id     BIGINT       NOT NULL,
    goods_name      VARCHAR(128) NOT NULL,
    goods_subtitle  VARCHAR(255) NULL,
    main_image      VARCHAR(255) NOT NULL COMMENT '主图,列表页使用',
    detail_content  TEXT         NULL COMMENT '商品详情富文本/HTML',
    sale_price_min  DECIMAL(10,2) NOT NULL COMMENT '起售价,取所有SKU最低价,用于列表页展示,SKU价格变动时同步更新',
    sale_price_max  DECIMAL(10,2) NOT NULL COMMENT '最高价,SKU间价格有差异时列表页展示"XX起"或区间',
    total_stock     INT          NOT NULL DEFAULT 0 COMMENT '所有SKU库存汇总,冗余字段,SKU库存变动时同步更新,避免列表页逐个SKU求和',
    sale_count      INT          NOT NULL DEFAULT 0 COMMENT '累计销量冗余字段',
    freight_template_id BIGINT   NULL COMMENT '关联运费模板,为空表示包邮',
    status          TINYINT      NOT NULL DEFAULT 0 COMMENT '0-下架 1-上架',
    sort_order      INT          NOT NULL DEFAULT 0,
    create_time     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    create_by       BIGINT       NULL COMMENT '关联sys_user.id,商家后台操作人',
    update_by       BIGINT       NULL,
    KEY idx_tenant_category (tenant_id, category_id),
    KEY idx_tenant_status (tenant_id, status)
) COMMENT '商品主表';

CREATE TABLE mall_goods_image (
    id              BIGINT PRIMARY KEY COMMENT '雪花ID,应用层生成',
    tenant_id       BIGINT       NOT NULL,
    goods_id        BIGINT       NOT NULL,
    image_url       VARCHAR(255) NOT NULL,
    sort_order      INT          NOT NULL DEFAULT 0,
    KEY idx_goods (goods_id)
) COMMENT '商品轮播图,与主图(mall_goods.main_image)分开维护';

CREATE TABLE mall_goods_spec (
    id              BIGINT PRIMARY KEY COMMENT '雪花ID,应用层生成',
    tenant_id       BIGINT       NOT NULL,
    goods_id        BIGINT       NOT NULL,
    spec_name       VARCHAR(32)  NOT NULL COMMENT '规格名,如"颜色""尺码"',
    sort_order      INT          NOT NULL DEFAULT 0,
    KEY idx_goods (goods_id)
) COMMENT '商品规格名,一个商品可有多组规格(颜色、尺码等)';

CREATE TABLE mall_goods_spec_value (
    id              BIGINT PRIMARY KEY COMMENT '雪花ID,应用层生成',
    tenant_id       BIGINT       NOT NULL,
    spec_id         BIGINT       NOT NULL,
    spec_value      VARCHAR(32)  NOT NULL COMMENT '规格值,如"红色""XL"',
    sort_order      INT          NOT NULL DEFAULT 0,
    KEY idx_spec (spec_id)
) COMMENT '规格值';

CREATE TABLE mall_sku (
    id              BIGINT PRIMARY KEY COMMENT '雪花ID,应用层生成',
    tenant_id       BIGINT       NOT NULL,
    goods_id        BIGINT       NOT NULL,
    sku_code        VARCHAR(64)  NOT NULL COMMENT '商家自定义SKU编码,同一租户下唯一',
    sku_name        VARCHAR(128) NOT NULL COMMENT '规格值组合拼接展示,如"红色/XL",冗余字段避免每次查关联表拼接',
    sku_image       VARCHAR(255) NULL COMMENT '该规格对应的图,为空则用商品主图',
    price           DECIMAL(10,2) NOT NULL,
    cost_price      DECIMAL(10,2) NULL COMMENT '成本价,用于毛利统计,不对客户端暴露',
    stock           INT          NOT NULL DEFAULT 0 COMMENT '实际库存',
    locked_stock    INT          NOT NULL DEFAULT 0 COMMENT '下单未支付锁定的库存,可售库存=stock-locked_stock',
    weight          DECIMAL(10,3) NULL COMMENT '重量(kg),按重量计运费时使用',
    status          TINYINT      NOT NULL DEFAULT 1 COMMENT '0-停售 1-正常',
    create_time     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_tenant_sku_code (tenant_id, sku_code),
    KEY idx_goods (goods_id)
) COMMENT 'SKU,多规格价格与库存的实际载体';

CREATE TABLE mall_sku_spec_value (
    sku_id          BIGINT NOT NULL,
    spec_value_id   BIGINT NOT NULL,
    PRIMARY KEY (sku_id, spec_value_id)
) COMMENT 'SKU与规格值的组合关系,一个SKU对应每组规格各一个值';

CREATE TABLE mall_goods_review (
    id              BIGINT PRIMARY KEY COMMENT '雪花ID,应用层生成',
    tenant_id       BIGINT       NOT NULL,
    goods_id        BIGINT       NOT NULL,
    order_item_id   BIGINT       NOT NULL COMMENT '关联到具体订单明细行,一个订单明细只能评价一次,应用层校验',
    customer_id     BIGINT       NOT NULL,
    rating          TINYINT      NOT NULL COMMENT '1-5星',
    content         VARCHAR(500) NULL,
    images          TEXT         NULL COMMENT '晒图URL列表,JSON数组',
    is_anonymous    TINYINT      NOT NULL DEFAULT 0,
    reply_content   VARCHAR(500) NULL COMMENT '商家回复',
    reply_time      DATETIME     NULL,
    status          TINYINT      NOT NULL DEFAULT 1 COMMENT '0-隐藏(违规下架) 1-展示',
    create_time     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY idx_goods (goods_id),
    KEY idx_order_item (order_item_id)
) COMMENT '商品评价';

-- ============================================================
-- 库存流水
-- ============================================================
CREATE TABLE mall_stock_log (
    id              BIGINT PRIMARY KEY COMMENT '雪花ID,应用层生成',
    tenant_id       BIGINT       NOT NULL,
    sku_id          BIGINT       NOT NULL,
    change_type     TINYINT      NOT NULL COMMENT '1-下单锁定 2-超时释放 3-支付扣减实际库存 4-退款回库 5-管理端手动调整',
    change_stock    INT          NOT NULL COMMENT '变动数量,正数增加负数减少',
    change_locked   INT          NOT NULL DEFAULT 0 COMMENT 'locked_stock的变动量',
    biz_id          BIGINT       NULL COMMENT '关联订单ID,手动调整时为空',
    remark          VARCHAR(255) NULL,
    create_time     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY idx_tenant_sku (tenant_id, sku_id)
) COMMENT '库存变动流水,stock/locked_stock的每次变化必须对应一条记录';

-- ============================================================
-- 购物车
-- ============================================================
CREATE TABLE mall_cart (
    id              BIGINT PRIMARY KEY COMMENT '雪花ID,应用层生成',
    tenant_id       BIGINT       NOT NULL,
    customer_id     BIGINT       NOT NULL,
    sku_id          BIGINT       NOT NULL,
    quantity        INT          NOT NULL,
    selected        TINYINT      NOT NULL DEFAULT 1 COMMENT '结算页是否勾选,1-选中',
    create_time     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_customer_sku (customer_id, sku_id) COMMENT '同一客户同一SKU只有一行,加购时数量累加'
) COMMENT '购物车';

-- ============================================================
-- 运费模板
-- ============================================================
CREATE TABLE mall_freight_template (
    id              BIGINT PRIMARY KEY COMMENT '雪花ID,应用层生成',
    tenant_id       BIGINT       NOT NULL,
    template_name   VARCHAR(64)  NOT NULL,
    charge_type     TINYINT      NOT NULL COMMENT '1-按件数 2-按重量',
    create_time     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    KEY idx_tenant (tenant_id)
) COMMENT '运费模板主表';

CREATE TABLE mall_freight_template_rule (
    id              BIGINT PRIMARY KEY COMMENT '雪花ID,应用层生成',
    tenant_id       BIGINT       NOT NULL,
    template_id     BIGINT       NOT NULL,
    region          VARCHAR(255) NOT NULL DEFAULT 'ALL' COMMENT '适用区域,ALL表示不限区域,否则按省份逗号分隔存储',
    first_unit      DECIMAL(10,3) NOT NULL COMMENT '首件/首重',
    first_fee       DECIMAL(10,2) NOT NULL COMMENT '首件/首重运费',
    additional_unit DECIMAL(10,3) NOT NULL COMMENT '续件/续重',
    additional_fee  DECIMAL(10,2) NOT NULL COMMENT '续件/续重运费',
    free_shipping_amount DECIMAL(10,2) NULL COMMENT '满多少金额包邮,为空表示不设包邮门槛',
    KEY idx_template (template_id)
) COMMENT '运费计费规则,按区域可设不同规则';

-- ============================================================
-- 优惠券 / 满减
-- ============================================================
CREATE TABLE mall_coupon (
    id              BIGINT PRIMARY KEY COMMENT '雪花ID,应用层生成',
    tenant_id       BIGINT       NOT NULL,
    coupon_name     VARCHAR(64)  NOT NULL,
    coupon_type     TINYINT      NOT NULL COMMENT '1-满减券 2-折扣券 3-无门槛现金券',
    discount_amount DECIMAL(10,2) NULL COMMENT 'coupon_type=1/3时使用,减免金额',
    discount_rate   DECIMAL(3,2) NULL COMMENT 'coupon_type=2时使用,如0.9表示9折',
    min_order_amount DECIMAL(10,2) NOT NULL DEFAULT 0 COMMENT '满多少可用,无门槛券填0',
    total_count     INT          NOT NULL COMMENT '发放总量',
    received_count  INT          NOT NULL DEFAULT 0 COMMENT '已领取数量,冗余字段防止总量超发',
    per_customer_limit INT       NOT NULL DEFAULT 1 COMMENT '每人限领数量',
    valid_start_time DATETIME    NOT NULL,
    valid_end_time  DATETIME     NOT NULL,
    status          TINYINT      NOT NULL DEFAULT 1 COMMENT '0-已停用 1-进行中',
    create_time     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    KEY idx_tenant (tenant_id)
) COMMENT '优惠券定义';

CREATE TABLE mall_coupon_record (
    id              BIGINT PRIMARY KEY COMMENT '雪花ID,应用层生成',
    tenant_id       BIGINT       NOT NULL,
    coupon_id       BIGINT       NOT NULL,
    customer_id     BIGINT       NOT NULL,
    status          TINYINT      NOT NULL DEFAULT 1 COMMENT '1-未使用 2-已使用 3-已过期',
    order_id        BIGINT       NULL COMMENT '使用时关联的订单,未使用为空',
    receive_time    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    use_time        DATETIME     NULL,
    KEY idx_tenant_customer (tenant_id, customer_id),
    KEY idx_coupon (coupon_id)
) COMMENT '优惠券领取/使用记录,与定义表分开,一张券定义对应多条领取记录';

CREATE TABLE mall_promotion_full_reduction (
    id              BIGINT PRIMARY KEY COMMENT '雪花ID,应用层生成',
    tenant_id       BIGINT       NOT NULL,
    activity_name   VARCHAR(64)  NOT NULL,
    reduction_rule  TEXT         NOT NULL COMMENT '满减阶梯规则,JSON数组,如[{"amount":100,"reduce":10},{"amount":200,"reduce":30}]',
    scope_type      TINYINT      NOT NULL DEFAULT 1 COMMENT '1-全部商品 2-指定分类 3-指定商品',
    valid_start_time DATETIME    NOT NULL,
    valid_end_time  DATETIME     NOT NULL,
    status          TINYINT      NOT NULL DEFAULT 1,
    create_time     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    KEY idx_tenant (tenant_id)
) COMMENT '满减活动';

CREATE TABLE mall_promotion_full_reduction_scope (
    id              BIGINT PRIMARY KEY COMMENT '雪花ID,应用层生成',
    tenant_id       BIGINT       NOT NULL,
    activity_id     BIGINT       NOT NULL,
    scope_id        BIGINT       NOT NULL COMMENT '按activity.scope_type,存category_id或goods_id',
    KEY idx_activity (activity_id)
) COMMENT '满减活动适用范围,scope_type=1(全部商品)时本表不需要记录';

-- ============================================================
-- 订单
-- ============================================================
CREATE TABLE mall_order (
    id                  BIGINT PRIMARY KEY COMMENT '雪花ID,应用层生成',
    tenant_id           BIGINT       NOT NULL,
    order_no            VARCHAR(32)  NOT NULL COMMENT '对外展示单号,格式见3.6,不用雪花ID直接展示',
    customer_id         BIGINT       NOT NULL,
    status              TINYINT      NOT NULL COMMENT '见3.4订单状态机',
    goods_amount        DECIMAL(10,2) NOT NULL COMMENT '商品总金额,下单时各SKU价格快照求和',
    freight_amount      DECIMAL(10,2) NOT NULL DEFAULT 0,
    coupon_discount_amount DECIMAL(10,2) NOT NULL DEFAULT 0,
    promotion_discount_amount DECIMAL(10,2) NOT NULL DEFAULT 0 COMMENT '满减活动减免金额',
    pay_amount          DECIMAL(10,2) NOT NULL COMMENT '实付金额=goods_amount+freight_amount-两项优惠',
    coupon_record_id    BIGINT       NULL COMMENT '使用的优惠券领取记录',
    receiver_name       VARCHAR(32)  NOT NULL COMMENT '下单时收货信息快照,不关联地址表(地址后续可能被删改)',
    receiver_phone      VARCHAR(20)  NOT NULL,
    receiver_address    VARCHAR(255) NOT NULL COMMENT '省市区+详细地址拼接快照',
    remark              VARCHAR(255) NULL COMMENT '买家留言',
    logistics_company   VARCHAR(64)  NULL,
    logistics_no        VARCHAR(64)  NULL,
    ship_time           DATETIME     NULL,
    receive_time        DATETIME     NULL COMMENT '确认收货时间',
    pay_time            DATETIME     NULL,
    cancel_time         DATETIME     NULL,
    close_reason        TINYINT      NULL COMMENT '1-超时未支付 2-买家取消 3-商家取消',
    finish_time         DATETIME     NULL,
    create_time         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_tenant_order_no (tenant_id, order_no),
    KEY idx_tenant_customer (tenant_id, customer_id),
    KEY idx_tenant_status (tenant_id, status)
) COMMENT '订单主表';

CREATE TABLE mall_order_item (
    id              BIGINT PRIMARY KEY COMMENT '雪花ID,应用层生成',
    tenant_id       BIGINT       NOT NULL,
    order_id        BIGINT       NOT NULL,
    sku_id          BIGINT       NOT NULL,
    goods_id        BIGINT       NOT NULL COMMENT '冗余,售后/评价按goods_id查询更方便',
    goods_name      VARCHAR(128) NOT NULL COMMENT '下单时快照,商品改名不影响历史订单展示',
    sku_name        VARCHAR(128) NOT NULL COMMENT '下单时快照',
    goods_image     VARCHAR(255) NOT NULL COMMENT '下单时快照',
    price           DECIMAL(10,2) NOT NULL COMMENT '下单时单价快照',
    quantity        INT          NOT NULL,
    total_amount    DECIMAL(10,2) NOT NULL COMMENT 'price*quantity',
    after_sale_status TINYINT    NOT NULL DEFAULT 0 COMMENT '0-无售后 1-售后处理中 2-售后已完成,避免每次查mall_after_sale判断入口是否可点',
    KEY idx_order (order_id),
    KEY idx_tenant_goods (tenant_id, goods_id)
) COMMENT '订单明细,商品/SKU信息全部落快照,不实时关联主表';

CREATE TABLE mall_order_status_log (
    id              BIGINT PRIMARY KEY COMMENT '雪花ID,应用层生成',
    tenant_id       BIGINT       NOT NULL,
    order_id        BIGINT       NOT NULL,
    from_status     TINYINT      NULL,
    to_status       TINYINT      NOT NULL,
    operator_type   TINYINT      NOT NULL COMMENT '1-买家 2-商家 3-系统自动',
    operator_id     BIGINT       NULL,
    remark          VARCHAR(255) NULL,
    create_time     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY idx_order (order_id)
) COMMENT '订单状态流转记录';

-- ============================================================
-- 微信支付 / 退款
-- ============================================================
CREATE TABLE mall_wx_payment (
    id              BIGINT PRIMARY KEY COMMENT '雪花ID,应用层生成',
    tenant_id       BIGINT       NOT NULL,
    order_id        BIGINT       NOT NULL,
    wx_transaction_id VARCHAR(64) NULL COMMENT '微信支付订单号,回调后写入',
    out_trade_no    VARCHAR(32)  NOT NULL COMMENT '商户订单号,等于mall_order.order_no',
    pay_amount      DECIMAL(10,2) NOT NULL,
    pay_status      TINYINT      NOT NULL DEFAULT 0 COMMENT '0-待支付 1-支付成功 2-支付失败/已关闭',
    prepay_id       VARCHAR(64)  NULL COMMENT '统一下单接口返回的预支付ID',
    callback_time   DATETIME     NULL,
    raw_callback    TEXT         NULL COMMENT '微信回调原始报文,便于对账排查,不做结构化解析存储',
    create_time     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_wx_transaction (wx_transaction_id) COMMENT '防止同一笔支付回调重复处理造成重复入账,见4.3幂等设计',
    UNIQUE KEY uk_tenant_out_trade_no (tenant_id, out_trade_no),
    KEY idx_order (order_id)
) COMMENT '微信支付流水';

CREATE TABLE mall_wx_refund (
    id              BIGINT PRIMARY KEY COMMENT '雪花ID,应用层生成',
    tenant_id       BIGINT       NOT NULL,
    order_id        BIGINT       NOT NULL,
    after_sale_id   BIGINT       NOT NULL,
    wx_refund_id    VARCHAR(64)  NULL COMMENT '微信退款单号,回调后写入',
    out_refund_no   VARCHAR(32)  NOT NULL COMMENT '商户退款单号',
    refund_amount   DECIMAL(10,2) NOT NULL,
    refund_status   TINYINT      NOT NULL DEFAULT 0 COMMENT '0-申请中 1-退款成功 2-退款失败',
    callback_time   DATETIME     NULL,
    raw_callback    TEXT         NULL,
    create_time     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_wx_refund (wx_refund_id),
    UNIQUE KEY uk_tenant_out_refund_no (tenant_id, out_refund_no),
    KEY idx_after_sale (after_sale_id)
) COMMENT '微信退款流水,与支付流水分表,字段不同不要合并';

-- ============================================================
-- 售后
-- ============================================================
CREATE TABLE mall_after_sale (
    id                          BIGINT PRIMARY KEY COMMENT '雪花ID,应用层生成',
    tenant_id                   BIGINT       NOT NULL,
    after_sale_no               VARCHAR(32)  NOT NULL COMMENT '对外展示单号',
    order_id                    BIGINT       NOT NULL,
    order_item_id               BIGINT       NOT NULL,
    customer_id                 BIGINT       NOT NULL,
    after_sale_type             TINYINT      NOT NULL COMMENT '1-仅退款 2-退货退款 3-换货',
    status                      TINYINT      NOT NULL COMMENT '见4.5售后状态机,1/2/3/4/5/6/7/8/9/10',
    apply_reason                VARCHAR(64)  NOT NULL,
    apply_desc                  VARCHAR(500) NULL,
    refund_amount               DECIMAL(10,2) NOT NULL COMMENT '申请退款金额,商家审核时可改小,不可改大,改动记入日志',
    reject_reason               VARCHAR(255) NULL COMMENT '商家拒绝申请或拒绝收货均用此字段,靠status区分',
    return_logistics_company    VARCHAR(64)  NULL,
    return_logistics_no         VARCHAR(64)  NULL,
    return_time                 DATETIME     NULL,
    receive_confirm_time        DATETIME     NULL,
    reship_logistics_company    VARCHAR(64)  NULL COMMENT '换货场景商家重新发货',
    reship_logistics_no         VARCHAR(64)  NULL,
    arbitration_time            DATETIME     NULL,
    arbitration_remark          VARCHAR(500) NULL,
    finish_time                 DATETIME     NULL,
    create_time                 DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time                 DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_tenant_after_sale_no (tenant_id, after_sale_no),
    KEY idx_order (order_id),
    KEY idx_order_item (order_item_id)
) COMMENT '售后单主表';

CREATE TABLE mall_after_sale_log (
    id              BIGINT PRIMARY KEY COMMENT '雪花ID,应用层生成',
    tenant_id       BIGINT       NOT NULL,
    after_sale_id   BIGINT       NOT NULL,
    from_status     TINYINT      NULL,
    to_status       TINYINT      NOT NULL,
    operator_type   TINYINT      NOT NULL COMMENT '1-买家 2-商家 3-系统自动 4-平台客服',
    operator_id     BIGINT       NULL,
    remark          VARCHAR(255) NULL,
    create_time     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY idx_after_sale (after_sale_id)
) COMMENT '售后状态流转记录,与mall_order_status_log是两条独立审计链';

CREATE TABLE mall_after_sale_image (
    id              BIGINT PRIMARY KEY COMMENT '雪花ID,应用层生成',
    tenant_id       BIGINT       NOT NULL,
    after_sale_id   BIGINT       NOT NULL,
    image_url       VARCHAR(255) NOT NULL,
    uploader_type   TINYINT      NOT NULL COMMENT '1-买家 2-商家',
    stage           TINYINT      NOT NULL COMMENT '1-申请时凭证 2-拒绝收货争议凭证',
    create_time     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY idx_after_sale (after_sale_id)
) COMMENT '售后凭证图片';

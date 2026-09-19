-- ============================================================
-- 微信小商城业务建表(架构文档见 mall_architecture.md,设计稿见仓库根的 mall_schema.sql)
--
-- 与设计稿的三处落地调整(都不改变业务语义,只补基础设施的硬约束):
--
-- 1) **所有表统一带 id / tenant_id / create_time / update_time / create_by / update_by**。
--    设计稿里多数表只写了 create_time(甚至只有 id)。但本项目所有租户级实体都继承
--    BaseTenantEntity(4.6),它要求这六列齐全;缺列的实体一落库就会报 unknown column。
--    流水/日志类表也一并补上:多两列换"所有 mall 实体同一套基类、同一套租户过滤",
--    比"为了省两列而混用不同基类"划算得多(4.6 明确警告过混用基类的后果:不走基类就没有过滤)。
--
-- 2) mall_sku_spec_value 补了 tenant_id,并把 (sku_id, spec_value_id) 由复合主键改成唯一键、
--    另加代理主键 id。两点原因:①设计稿里它是唯一不带租户列的表,而文件头注释本身承诺了
--    "所有表均带 tenant_id",少了它就成了"带过滤器实体之外的一环"——绕过 mall_sku 直接查它就不受隔离;
--    ②它原本主键全由业务列组成,只能单独用 @IdClass 映射、无法复用公共基类(审计列要手写一遍)。
--    改成代理主键后 28 张表同一套基类,关系的唯一性由唯一键保证,语义不变。
--
-- 3) 部分单列索引补成 (tenant_id, xxx) 复合索引。租户过滤条件是 tenant_id = ?,
--    会挂在每一个查询上;索引不带 tenant_id 时,MySQL 走单列索引命中的行还要回表过滤租户,
--    租户数据一多就退化成大量无效回表。
--
-- 另外:mall_* 表**不参与数据权限**(5.3 的 data_scope 是后台组织架构语义),
-- 也**不实现 OwnedEntity**(归属人校验针对后台操作,商城侧操作人是 mall_customer,
-- 身份记在各自的 customer_id 业务列里,不复用 create_by 的语义)。
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
    gender          INT          NULL COMMENT '0-未知 1-男 2-女',
    status          INT          NOT NULL DEFAULT 1 COMMENT '0-禁用 1-正常',
    member_level_id BIGINT       NULL COMMENT '当前会员等级,为空表示默认等级(不建默认等级记录,应用层兜底展示为"普通会员")',
    points          INT          NOT NULL DEFAULT 0 COMMENT '当前可用积分,变动必须同时写 mall_points_log,不允许绕过流水直接改这个字段',
    growth_value    INT          NOT NULL DEFAULT 0 COMMENT '成长值,用于等级晋升判断,累计不清零(区别于积分可消耗)',
    register_time   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_login_time DATETIME     NULL,
    create_time     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    create_by       BIGINT       NULL,
    update_by       BIGINT       NULL,
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
    is_default      INT          NOT NULL DEFAULT 0 COMMENT '1-默认地址,同一客户至多一条为1,应用层保证',
    create_time     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    create_by       BIGINT       NULL,
    update_by       BIGINT       NULL,
    KEY idx_tenant_customer (tenant_id, customer_id)
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
    status          INT          NOT NULL DEFAULT 1,
    create_time     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    create_by       BIGINT       NULL,
    update_by       BIGINT       NULL,
    KEY idx_tenant (tenant_id)
) COMMENT '会员等级定义,本期只建字段,晋升/权益逻辑不在本期实现范围';

CREATE TABLE mall_points_log (
    id              BIGINT PRIMARY KEY COMMENT '雪花ID,应用层生成',
    tenant_id       BIGINT       NOT NULL,
    customer_id     BIGINT       NOT NULL,
    change_points   INT          NOT NULL COMMENT '变动数量,正数为增加负数为减少',
    balance_points  INT          NOT NULL COMMENT '变动后余额,便于对账,不用每次汇总流水计算',
    biz_type        INT          NOT NULL COMMENT '1-下单获得 2-兑换消耗 3-退款扣回 4-管理端手动调整 5-过期清零',
    biz_id          BIGINT       NULL COMMENT '关联业务ID,如订单ID,管理端手动调整时为空',
    remark          VARCHAR(255) NULL,
    create_time     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    create_by       BIGINT       NULL,
    update_by       BIGINT       NULL,
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
    status          INT          NOT NULL DEFAULT 1,
    create_time     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    create_by       BIGINT       NULL,
    update_by       BIGINT       NULL,
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
    status          INT          NOT NULL DEFAULT 0 COMMENT '0-下架 1-上架',
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
    create_time     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    create_by       BIGINT       NULL,
    update_by       BIGINT       NULL,
    KEY idx_tenant_goods (tenant_id, goods_id)
) COMMENT '商品轮播图,与主图(mall_goods.main_image)分开维护';

CREATE TABLE mall_goods_spec (
    id              BIGINT PRIMARY KEY COMMENT '雪花ID,应用层生成',
    tenant_id       BIGINT       NOT NULL,
    goods_id        BIGINT       NOT NULL,
    spec_name       VARCHAR(32)  NOT NULL COMMENT '规格名,如"颜色""尺码"',
    sort_order      INT          NOT NULL DEFAULT 0,
    create_time     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    create_by       BIGINT       NULL,
    update_by       BIGINT       NULL,
    KEY idx_tenant_goods (tenant_id, goods_id)
) COMMENT '商品规格名,一个商品可有多组规格(颜色、尺码等)';

CREATE TABLE mall_goods_spec_value (
    id              BIGINT PRIMARY KEY COMMENT '雪花ID,应用层生成',
    tenant_id       BIGINT       NOT NULL,
    spec_id         BIGINT       NOT NULL,
    spec_value      VARCHAR(32)  NOT NULL COMMENT '规格值,如"红色""XL"',
    sort_order      INT          NOT NULL DEFAULT 0,
    create_time     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    create_by       BIGINT       NULL,
    update_by       BIGINT       NULL,
    KEY idx_tenant_spec (tenant_id, spec_id)
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
    status          INT          NOT NULL DEFAULT 1 COMMENT '0-停售 1-正常',
    create_time     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    create_by       BIGINT       NULL,
    update_by       BIGINT       NULL,
    UNIQUE KEY uk_tenant_sku_code (tenant_id, sku_code),
    KEY idx_tenant_goods (tenant_id, goods_id)
) COMMENT 'SKU,多规格价格与库存的实际载体';

CREATE TABLE mall_sku_spec_value (
    id              BIGINT       PRIMARY KEY COMMENT '雪花ID,应用层生成',
    tenant_id       BIGINT       NOT NULL,
    sku_id          BIGINT       NOT NULL,
    spec_value_id   BIGINT       NOT NULL,
    create_time     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    create_by       BIGINT       NULL,
    update_by       BIGINT       NULL,
    UNIQUE KEY uk_sku_spec_value (sku_id, spec_value_id) COMMENT '"一个SKU每组规格只能取一个值"由唯一键保证',
    KEY idx_tenant_sku (tenant_id, sku_id)
) COMMENT 'SKU与规格值的组合关系,一个SKU对应每组规格各一个值';

CREATE TABLE mall_goods_review (
    id              BIGINT PRIMARY KEY COMMENT '雪花ID,应用层生成',
    tenant_id       BIGINT       NOT NULL,
    goods_id        BIGINT       NOT NULL,
    order_item_id   BIGINT       NOT NULL COMMENT '关联到具体订单明细行,一个订单明细只能评价一次,应用层校验',
    customer_id     BIGINT       NOT NULL,
    rating          INT          NOT NULL COMMENT '1-5星',
    content         VARCHAR(500) NULL,
    images          TEXT         NULL COMMENT '晒图URL列表,JSON数组',
    is_anonymous    INT          NOT NULL DEFAULT 0,
    reply_content   VARCHAR(500) NULL COMMENT '商家回复',
    reply_time      DATETIME     NULL,
    status          INT          NOT NULL DEFAULT 1 COMMENT '0-隐藏(违规下架) 1-展示',
    create_time     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    create_by       BIGINT       NULL,
    update_by       BIGINT       NULL,
    KEY idx_tenant_goods (tenant_id, goods_id),
    KEY idx_tenant_order_item (tenant_id, order_item_id)
) COMMENT '商品评价';

-- ============================================================
-- 库存流水
-- ============================================================
CREATE TABLE mall_stock_log (
    id              BIGINT PRIMARY KEY COMMENT '雪花ID,应用层生成',
    tenant_id       BIGINT       NOT NULL,
    sku_id          BIGINT       NOT NULL,
    change_type     INT          NOT NULL COMMENT '1-下单锁定 2-超时释放 3-支付扣减实际库存 4-退款回库 5-管理端手动调整',
    change_stock    INT          NOT NULL COMMENT '变动数量,正数增加负数减少',
    change_locked   INT          NOT NULL DEFAULT 0 COMMENT 'locked_stock的变动量',
    biz_id          BIGINT       NULL COMMENT '关联订单ID,手动调整时为空',
    remark          VARCHAR(255) NULL,
    create_time     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    create_by       BIGINT       NULL,
    update_by       BIGINT       NULL,
    KEY idx_tenant_sku (tenant_id, sku_id),
    KEY idx_tenant_biz (tenant_id, biz_id)
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
    selected        INT          NOT NULL DEFAULT 1 COMMENT '结算页是否勾选,1-选中',
    create_time     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    create_by       BIGINT       NULL,
    update_by       BIGINT       NULL,
    UNIQUE KEY uk_tenant_customer_sku (tenant_id, customer_id, sku_id) COMMENT '同一客户同一SKU只有一行,加购时数量累加'
) COMMENT '购物车';

-- ============================================================
-- 运费模板
-- ============================================================
CREATE TABLE mall_freight_template (
    id              BIGINT PRIMARY KEY COMMENT '雪花ID,应用层生成',
    tenant_id       BIGINT       NOT NULL,
    template_name   VARCHAR(64)  NOT NULL,
    charge_type     INT          NOT NULL COMMENT '1-按件数 2-按重量',
    create_time     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    create_by       BIGINT       NULL,
    update_by       BIGINT       NULL,
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
    create_time     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    create_by       BIGINT       NULL,
    update_by       BIGINT       NULL,
    KEY idx_tenant_template (tenant_id, template_id)
) COMMENT '运费计费规则,按区域可设不同规则';

-- ============================================================
-- 优惠券 / 满减
-- ============================================================
CREATE TABLE mall_coupon (
    id              BIGINT PRIMARY KEY COMMENT '雪花ID,应用层生成',
    tenant_id       BIGINT       NOT NULL,
    coupon_name     VARCHAR(64)  NOT NULL,
    coupon_type     INT          NOT NULL COMMENT '1-满减券 2-折扣券 3-无门槛现金券',
    discount_amount DECIMAL(10,2) NULL COMMENT 'coupon_type=1/3时使用,减免金额',
    discount_rate   DECIMAL(3,2) NULL COMMENT 'coupon_type=2时使用,如0.9表示9折',
    min_order_amount DECIMAL(10,2) NOT NULL DEFAULT 0 COMMENT '满多少可用,无门槛券填0',
    total_count     INT          NOT NULL COMMENT '发放总量',
    received_count  INT          NOT NULL DEFAULT 0 COMMENT '已领取数量,冗余字段防止总量超发',
    per_customer_limit INT       NOT NULL DEFAULT 1 COMMENT '每人限领数量',
    valid_start_time DATETIME    NOT NULL,
    valid_end_time  DATETIME     NOT NULL,
    status          INT          NOT NULL DEFAULT 1 COMMENT '0-已停用 1-进行中',
    create_time     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    create_by       BIGINT       NULL,
    update_by       BIGINT       NULL,
    KEY idx_tenant (tenant_id)
) COMMENT '优惠券定义';

CREATE TABLE mall_coupon_record (
    id              BIGINT PRIMARY KEY COMMENT '雪花ID,应用层生成',
    tenant_id       BIGINT       NOT NULL,
    coupon_id       BIGINT       NOT NULL,
    customer_id     BIGINT       NOT NULL,
    status          INT          NOT NULL DEFAULT 1 COMMENT '1-未使用 2-已使用 3-已过期',
    order_id        BIGINT       NULL COMMENT '使用时关联的订单,未使用为空',
    receive_time    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    use_time        DATETIME     NULL,
    create_time     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    create_by       BIGINT       NULL,
    update_by       BIGINT       NULL,
    KEY idx_tenant_customer (tenant_id, customer_id),
    KEY idx_tenant_coupon (tenant_id, coupon_id)
) COMMENT '优惠券领取/使用记录,与定义表分开,一张券定义对应多条领取记录';

CREATE TABLE mall_promotion_full_reduction (
    id              BIGINT PRIMARY KEY COMMENT '雪花ID,应用层生成',
    tenant_id       BIGINT       NOT NULL,
    activity_name   VARCHAR(64)  NOT NULL,
    reduction_rule  TEXT         NOT NULL COMMENT '满减阶梯规则,JSON数组,如[{"amount":100,"reduce":10},{"amount":200,"reduce":30}]',
    scope_type      INT          NOT NULL DEFAULT 1 COMMENT '1-全部商品 2-指定分类 3-指定商品',
    valid_start_time DATETIME    NOT NULL,
    valid_end_time  DATETIME     NOT NULL,
    status          INT          NOT NULL DEFAULT 1,
    create_time     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    create_by       BIGINT       NULL,
    update_by       BIGINT       NULL,
    KEY idx_tenant (tenant_id)
) COMMENT '满减活动';

CREATE TABLE mall_promotion_full_reduction_scope (
    id              BIGINT PRIMARY KEY COMMENT '雪花ID,应用层生成',
    tenant_id       BIGINT       NOT NULL,
    activity_id     BIGINT       NOT NULL,
    scope_id        BIGINT       NOT NULL COMMENT '按activity.scope_type,存category_id或goods_id',
    create_time     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    create_by       BIGINT       NULL,
    update_by       BIGINT       NULL,
    KEY idx_tenant_activity (tenant_id, activity_id)
) COMMENT '满减活动适用范围,scope_type=1(全部商品)时本表不需要记录';

-- ============================================================
-- 订单
-- ============================================================
CREATE TABLE mall_order (
    id                  BIGINT PRIMARY KEY COMMENT '雪花ID,应用层生成',
    tenant_id           BIGINT       NOT NULL,
    order_no            VARCHAR(32)  NOT NULL COMMENT '对外展示单号,格式见3.6,不用雪花ID直接展示',
    customer_id         BIGINT       NOT NULL,
    status              INT          NOT NULL COMMENT '见3.4订单状态机',
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
    close_reason        INT          NULL COMMENT '1-超时未支付 2-买家取消 3-商家取消',
    finish_time         DATETIME     NULL,
    create_time         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    create_by           BIGINT       NULL,
    update_by           BIGINT       NULL,
    UNIQUE KEY uk_tenant_order_no (tenant_id, order_no),
    KEY idx_tenant_customer (tenant_id, customer_id),
    KEY idx_tenant_status (tenant_id, status),
    KEY idx_tenant_create_time (tenant_id, create_time) COMMENT '订单超时关闭任务按创建时间扫描待支付单'
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
    after_sale_status INT          NOT NULL DEFAULT 0 COMMENT '0-无售后 1-售后处理中 2-售后已完成,避免每次查mall_after_sale判断入口是否可点',
    create_time     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    create_by       BIGINT       NULL,
    update_by       BIGINT       NULL,
    KEY idx_tenant_order (tenant_id, order_id),
    KEY idx_tenant_goods (tenant_id, goods_id)
) COMMENT '订单明细,商品/SKU信息全部落快照,不实时关联主表';

CREATE TABLE mall_order_status_log (
    id              BIGINT PRIMARY KEY COMMENT '雪花ID,应用层生成',
    tenant_id       BIGINT       NOT NULL,
    order_id        BIGINT       NOT NULL,
    from_status     INT          NULL,
    to_status       INT          NOT NULL,
    operator_type   INT          NOT NULL COMMENT '1-买家 2-商家 3-系统自动',
    operator_id     BIGINT       NULL,
    remark          VARCHAR(255) NULL,
    create_time     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    create_by       BIGINT       NULL,
    update_by       BIGINT       NULL,
    KEY idx_tenant_order (tenant_id, order_id)
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
    pay_status      INT          NOT NULL DEFAULT 0 COMMENT '0-待支付 1-支付成功 2-支付失败/已关闭',
    prepay_id       VARCHAR(64)  NULL COMMENT '统一下单接口返回的预支付ID',
    callback_time   DATETIME     NULL,
    raw_callback    TEXT         NULL COMMENT '微信回调原始报文,便于对账排查,不做结构化解析存储',
    create_time     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    create_by       BIGINT       NULL,
    update_by       BIGINT       NULL,
    UNIQUE KEY uk_wx_transaction (wx_transaction_id) COMMENT '防止同一笔支付回调重复处理造成重复入账,见3.8幂等设计',
    UNIQUE KEY uk_tenant_out_trade_no (tenant_id, out_trade_no),
    KEY idx_tenant_order (tenant_id, order_id)
) COMMENT '微信支付流水';

CREATE TABLE mall_wx_refund (
    id              BIGINT PRIMARY KEY COMMENT '雪花ID,应用层生成',
    tenant_id       BIGINT       NOT NULL,
    order_id        BIGINT       NOT NULL,
    after_sale_id   BIGINT       NOT NULL,
    wx_refund_id    VARCHAR(64)  NULL COMMENT '微信退款单号,回调后写入',
    out_refund_no   VARCHAR(32)  NOT NULL COMMENT '商户退款单号',
    refund_amount   DECIMAL(10,2) NOT NULL,
    refund_status   INT          NOT NULL DEFAULT 0 COMMENT '0-申请中 1-退款成功 2-退款失败',
    callback_time   DATETIME     NULL,
    raw_callback    TEXT         NULL,
    create_time     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    create_by       BIGINT       NULL,
    update_by       BIGINT       NULL,
    UNIQUE KEY uk_wx_refund (wx_refund_id),
    UNIQUE KEY uk_tenant_out_refund_no (tenant_id, out_refund_no),
    KEY idx_tenant_after_sale (tenant_id, after_sale_id)
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
    after_sale_type             INT          NOT NULL COMMENT '1-仅退款 2-退货退款 3-换货',
    status                      INT          NOT NULL COMMENT '见3.9售后状态机,1/2/3/4/5/6/7/8/9/10',
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
    create_by                   BIGINT       NULL,
    update_by                   BIGINT       NULL,
    UNIQUE KEY uk_tenant_after_sale_no (tenant_id, after_sale_no),
    KEY idx_tenant_order (tenant_id, order_id),
    KEY idx_tenant_order_item (tenant_id, order_item_id),
    KEY idx_tenant_status_update (tenant_id, status, update_time) COMMENT '售后超时任务按(status, 最后变动时间)扫描'
) COMMENT '售后单主表';

CREATE TABLE mall_after_sale_log (
    id              BIGINT PRIMARY KEY COMMENT '雪花ID,应用层生成',
    tenant_id       BIGINT       NOT NULL,
    after_sale_id   BIGINT       NOT NULL,
    from_status     INT          NULL,
    to_status       INT          NOT NULL,
    operator_type   INT          NOT NULL COMMENT '1-买家 2-商家 3-系统自动 4-平台客服',
    operator_id     BIGINT       NULL,
    remark          VARCHAR(255) NULL,
    create_time     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    create_by       BIGINT       NULL,
    update_by       BIGINT       NULL,
    KEY idx_tenant_after_sale (tenant_id, after_sale_id)
) COMMENT '售后状态流转记录,与mall_order_status_log是两条独立审计链';

CREATE TABLE mall_after_sale_image (
    id              BIGINT PRIMARY KEY COMMENT '雪花ID,应用层生成',
    tenant_id       BIGINT       NOT NULL,
    after_sale_id   BIGINT       NOT NULL,
    image_url       VARCHAR(255) NOT NULL,
    uploader_type   INT          NOT NULL COMMENT '1-买家 2-商家',
    stage           INT          NOT NULL COMMENT '1-申请时凭证 2-拒绝收货争议凭证',
    create_time     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    create_by       BIGINT       NULL,
    update_by       BIGINT       NULL,
    KEY idx_tenant_after_sale (tenant_id, after_sale_id)
) COMMENT '售后凭证图片';

-- ============================================================
-- 商城定时任务用到的可配置阈值(3.4、3.9)
--
-- 放在这里而不是写死在代码里:超时时间是最常被业务方要求调整的参数,
-- 写死意味着每次调整都要发版。字典模块(5.1)已经提供了维护入口与缓存。
-- ============================================================
INSERT INTO sys_dict_type (id, dict_type, dict_name) VALUES
    (101, 'order_pay_timeout_minutes', '订单支付超时分钟数'),
    (102, 'order_auto_receive_days', '订单自动确认收货天数'),
    (103, 'after_sale_timeout', '售后各环节超时小时数')
ON DUPLICATE KEY UPDATE dict_name = VALUES(dict_name);

INSERT INTO sys_dict_data (id, dict_type, dict_label, dict_value, sort_order) VALUES
    (101, 'order_pay_timeout_minutes', '15分钟', '15', 1),
    (102, 'order_auto_receive_days', '15天', '15', 1),
    (103, 'after_sale_timeout', '商家处理(小时)', '72', 1),
    (104, 'after_sale_timeout', '买家退货(天)', '7', 2),
    (105, 'after_sale_timeout', '商家收货(天)', '10', 3)
ON DUPLICATE KEY UPDATE dict_label = VALUES(dict_label),
                        dict_value = VALUES(dict_value),
                        sort_order = VALUES(sort_order);

-- ============================================================
-- 商城管理端菜单(权限码沿用 模块:资源:操作,见 mall_architecture.md 第 1 节)
--
-- 只建"目录 + 页面 + 按钮"三层里的前两层与最常用的按钮;随着各模块开发逐步补齐。
-- is_platform = 0:商城菜单是租户侧功能,会随套餐授权给租户(与字典菜单相反)。
-- ============================================================
INSERT INTO sys_menu (id, parent_id, menu_name, menu_type, route_path, perm_code, icon, is_platform, sort_order, status) VALUES
    (200, 0,   '商城管理', 1, '/mall',            NULL,                    'shopping', 0, 10, 1),
    (201, 200, '商品管理', 2, 'goods',            NULL,                    'shop',     0, 1, 1),
    (2011, 201, '商品列表', 3, NULL, 'mall:goods:list',      NULL, 0, 1, 1),
    (2012, 201, '商品新增', 3, NULL, 'mall:goods:create',    NULL, 0, 2, 1),
    (2013, 201, '商品修改', 3, NULL, 'mall:goods:update',    NULL, 0, 3, 1),
    (2014, 201, '商品删除', 3, NULL, 'mall:goods:delete',    NULL, 0, 4, 1),
    (2015, 201, '商品上下架', 3, NULL, 'mall:goods:status',  NULL, 0, 5, 1),
    (202, 200, '商品分类', 2, 'category',         NULL,                    'appstore', 0, 2, 1),
    (2021, 202, '分类列表', 3, NULL, 'mall:category:list',   NULL, 0, 1, 1),
    (2022, 202, '分类新增', 3, NULL, 'mall:category:create', NULL, 0, 2, 1),
    (2023, 202, '分类修改', 3, NULL, 'mall:category:update', NULL, 0, 3, 1),
    (2024, 202, '分类删除', 3, NULL, 'mall:category:delete', NULL, 0, 4, 1),
    (203, 200, '订单管理', 2, 'order',            NULL,                    'profile',  0, 3, 1),
    (2031, 203, '订单列表', 3, NULL, 'mall:order:list',      NULL, 0, 1, 1),
    (2032, 203, '订单发货', 3, NULL, 'mall:order:ship',      NULL, 0, 2, 1),
    (2033, 203, '订单取消', 3, NULL, 'mall:order:cancel',    NULL, 0, 3, 1),
    (204, 200, '售后管理', 2, 'after-sale',       NULL,                    'tool',     0, 4, 1),
    (2041, 204, '售后列表', 3, NULL, 'mall:after-sale:list', NULL, 0, 1, 1),
    (2042, 204, '售后处理', 3, NULL, 'mall:after-sale:handle', NULL, 0, 2, 1),
    (205, 200, '优惠券', 2, 'coupon',             NULL,                    'gift',     0, 5, 1),
    (2051, 205, '优惠券列表', 3, NULL, 'mall:coupon:list',   NULL, 0, 1, 1),
    (2052, 205, '优惠券新增', 3, NULL, 'mall:coupon:create', NULL, 0, 2, 1),
    (2053, 205, '优惠券修改', 3, NULL, 'mall:coupon:update', NULL, 0, 3, 1),
    (206, 200, '满减活动', 2, 'promotion',        NULL,                    'thunderbolt', 0, 6, 1),
    (2061, 206, '活动列表', 3, NULL, 'mall:promotion:list',  NULL, 0, 1, 1),
    (2062, 206, '活动新增', 3, NULL, 'mall:promotion:create', NULL, 0, 2, 1),
    (2063, 206, '活动修改', 3, NULL, 'mall:promotion:update', NULL, 0, 3, 1),
    (207, 200, '运费模板', 2, 'freight',          NULL,                    'car',      0, 7, 1),
    (2071, 207, '模板列表', 3, NULL, 'mall:freight:list',    NULL, 0, 1, 1),
    (2072, 207, '模板新增', 3, NULL, 'mall:freight:create',  NULL, 0, 2, 1),
    (2073, 207, '模板修改', 3, NULL, 'mall:freight:update',  NULL, 0, 3, 1),
    (2074, 207, '模板删除', 3, NULL, 'mall:freight:delete',  NULL, 0, 4, 1),
    (208, 200, '会员等级', 2, 'member-level',     NULL,                    'crown',    0, 8, 1),
    (2081, 208, '等级列表', 3, NULL, 'mall:member-level:list', NULL, 0, 1, 1),
    (2082, 208, '等级新增', 3, NULL, 'mall:member-level:create', NULL, 0, 2, 1),
    (2083, 208, '等级修改', 3, NULL, 'mall:member-level:update', NULL, 0, 3, 1),
    (209, 200, '商品评价', 2, 'review',           NULL,                    'star',     0, 9, 1),
    (2091, 209, '评价列表', 3, NULL, 'mall:review:list',     NULL, 0, 1, 1),
    (2092, 209, '评价回复', 3, NULL, 'mall:review:reply',    NULL, 0, 2, 1),
    (2093, 209, '评价隐藏', 3, NULL, 'mall:review:status',   NULL, 0, 3, 1)
ON DUPLICATE KEY UPDATE menu_name = VALUES(menu_name),
                        parent_id = VALUES(parent_id),
                        menu_type = VALUES(menu_type),
                        route_path = VALUES(route_path),
                        perm_code = VALUES(perm_code),
                        is_platform = VALUES(is_platform),
                        sort_order = VALUES(sort_order),
                        status = VALUES(status);

-- 商城菜单是非平台菜单(is_platform = 0),按 V2 的约定要落进"全量套餐",
-- 否则租户拿不到商城权限码 —— 与 V2 那句 SELECT 同理,数量随菜单自然增长。
INSERT INTO sys_package_menu (package_id, menu_id)
SELECT 1, id FROM sys_menu WHERE is_platform = 0
ON DUPLICATE KEY UPDATE menu_id = menu_id;

-- 平台管理员角色补齐商城菜单,保持与 V2 相同的授权状态。
INSERT INTO sys_role_menu (role_id, menu_id)
SELECT 1, id FROM sys_menu WHERE is_platform = 0
ON DUPLICATE KEY UPDATE menu_id = menu_id;

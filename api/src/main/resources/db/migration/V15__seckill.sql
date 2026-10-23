-- ============================================================
-- 秒杀(设计文档 3.12)
--
-- 三张表:活动、活动商品项、限购账本。
--   · 配额在发布时从 SKU 可售库存里"冻结"出来(frozen_stock),活动结束解冻未售出部分
--   · 限购账本一人一行,锁这一行做精确校验,不同用户互不阻塞
--   · 秒杀单只有商品项一个,取消/复活所需的 item_id + quantity 直接取 mall_order_item,
--     所以不再单独建"秒杀订单记录表"
-- ============================================================

ALTER TABLE mall_sku
    ADD COLUMN frozen_stock INT NOT NULL DEFAULT 0
        COMMENT '被已发布秒杀活动冻结、尚未被秒杀单占用的数量,可售库存=stock-locked_stock-frozen_stock'
        AFTER locked_stock;

ALTER TABLE mall_stock_log
    ADD COLUMN change_frozen INT NOT NULL DEFAULT 0 COMMENT 'frozen_stock 的变动量';

ALTER TABLE mall_stock_log
    MODIFY COLUMN change_type INT NOT NULL
        COMMENT '1-下单锁定 2-超时释放 3-支付扣减实际库存 4-退款回库 5-管理端手动调整 6-秒杀冻结 7-秒杀解冻';

ALTER TABLE mall_order
    ADD COLUMN order_type INT NOT NULL DEFAULT 1 COMMENT '1-普通订单 2-秒杀订单' AFTER status,
    ADD COLUMN seckill_activity_id BIGINT NULL COMMENT '秒杀活动,普通单为空' AFTER order_type;

ALTER TABLE mall_order_item
    ADD COLUMN seckill_item_id BIGINT NULL COMMENT '秒杀活动商品项,普通单为空' AFTER sku_id;

CREATE TABLE mall_seckill_activity (
    id              BIGINT PRIMARY KEY COMMENT '雪花ID,应用层生成',
    tenant_id       BIGINT       NOT NULL,
    activity_name   VARCHAR(64)  NOT NULL,
    start_time      DATETIME     NOT NULL,
    end_time        DATETIME     NOT NULL,
    status          INT          NOT NULL DEFAULT 0 COMMENT '0-草稿 1-待开始 2-进行中 3-已结束 4-已取消',
    remark          VARCHAR(255) NULL,
    sort_order      INT          NOT NULL DEFAULT 0,
    create_time     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    create_by       BIGINT       NULL,
    update_by       BIGINT       NULL,
    KEY idx_tenant_status_start (tenant_id, status, start_time),
    KEY idx_tenant_status_end (tenant_id, status, end_time)
) COMMENT '秒杀活动';

CREATE TABLE mall_seckill_item (
    id                  BIGINT PRIMARY KEY COMMENT '雪花ID,应用层生成',
    tenant_id           BIGINT        NOT NULL,
    activity_id         BIGINT        NOT NULL,
    goods_id            BIGINT        NOT NULL COMMENT '冗余,列表展示与跳转用',
    sku_id              BIGINT        NOT NULL,
    seckill_price       DECIMAL(10,2) NOT NULL,
    seckill_stock       INT           NOT NULL COMMENT '活动配额,发布时从 SKU 可售库存冻结',
    sold_count          INT           NOT NULL DEFAULT 0 COMMENT '已占用(下单即算,取消回退)',
    per_customer_limit  INT           NOT NULL DEFAULT 1 COMMENT '每人限购件数',
    sort_order          INT           NOT NULL DEFAULT 0,
    create_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    create_by           BIGINT        NULL,
    update_by           BIGINT        NULL,
    KEY idx_tenant_activity (tenant_id, activity_id),
    KEY idx_tenant_sku (tenant_id, sku_id)
) COMMENT '秒杀活动商品项';

CREATE TABLE mall_seckill_buyer (
    id              BIGINT PRIMARY KEY COMMENT '雪花ID,应用层生成',
    tenant_id       BIGINT NOT NULL,
    item_id         BIGINT NOT NULL,
    customer_id     BIGINT NOT NULL,
    bought_count    INT    NOT NULL DEFAULT 0 COMMENT '已购件数(取消回退)',
    create_time     DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time     DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    create_by       BIGINT NULL,
    update_by       BIGINT NULL,
    UNIQUE KEY uk_tenant_item_customer (tenant_id, item_id, customer_id)
) COMMENT '秒杀限购账本,一人一行';

-- 商城菜单是非平台菜单,要落进"全量套餐",否则租户拿不到权限码(与 V13 同理)
INSERT INTO sys_menu (id, parent_id, menu_name, menu_type, route_path, perm_code, icon, is_platform, sort_order, status) VALUES
    (213,   200, '秒杀管理', 2, 'seckill', NULL,                  'thunderbolt', 0, 12, 1),
    (2131,  213, '活动列表', 3, NULL,      'mall:seckill:list',    NULL, 0, 1, 1),
    (2132,  213, '新增活动', 3, NULL,      'mall:seckill:create',  NULL, 0, 2, 1),
    (2133,  213, '编辑活动', 3, NULL,      'mall:seckill:update',  NULL, 0, 3, 1),
    (2134,  213, '发布活动', 3, NULL,      'mall:seckill:publish', NULL, 0, 4, 1),
    (2135,  213, '取消活动', 3, NULL,      'mall:seckill:cancel',  NULL, 0, 5, 1) AS new
ON DUPLICATE KEY UPDATE menu_name = new.menu_name,
                        parent_id = new.parent_id,
                        menu_type = new.menu_type,
                        route_path = new.route_path,
                        perm_code = new.perm_code,
                        icon = new.icon,
                        is_platform = new.is_platform,
                        sort_order = new.sort_order,
                        status = new.status;

INSERT INTO sys_package_menu (package_id, menu_id)
SELECT 1, id FROM sys_menu WHERE is_platform = 0
ON DUPLICATE KEY UPDATE menu_id = menu_id;

INSERT INTO sys_role_menu (role_id, menu_id)
SELECT 1, id FROM sys_menu WHERE is_platform = 0
ON DUPLICATE KEY UPDATE menu_id = menu_id;

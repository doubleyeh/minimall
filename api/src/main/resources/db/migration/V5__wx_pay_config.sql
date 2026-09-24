-- ============================================================
-- 微信支付渠道配置(平台管理,每租户一行)
--
-- 为什么不沿用 sys_pay_account:那张表只有 channel/pay_account_id/tenant_id/status 四列,
-- 定位是"支付账号 → 租户"的映射(架构文档 6.1 的备选租户识别方案),不存密钥与证书。
--
-- 密钥类列一律存信封加密后的密文(见 SecretCipher),明文只在内存中出现;
-- 主密钥来自环境变量,库与缓存里都不留明文,所以"库被读到"与"缓存被读到"的损失等价。
-- ============================================================
CREATE TABLE sys_wx_pay_config (
    id                        BIGINT PRIMARY KEY COMMENT '雪花ID,应用层生成',
    tenant_id                 BIGINT       NOT NULL COMMENT '配置归属租户',
    pay_mode                  VARCHAR(16)  NOT NULL COMMENT 'direct-普通商户 partner-服务商',
    mch_id                    VARCHAR(32)  NOT NULL COMMENT 'direct:商户号;partner:服务商商户号 sp_mchid',
    sub_mch_id                VARCHAR(32)  NULL COMMENT 'partner:特约商户号 sub_mchid;direct 为空',
    app_id                    VARCHAR(32)  NOT NULL COMMENT 'direct:商户小程序 appid;partner:服务商小程序 sp_appid',
    app_secret_enc            VARCHAR(512) NOT NULL COMMENT 'app_id 对应的小程序 appSecret 密文',
    sub_app_id                VARCHAR(32)  NULL COMMENT 'partner:特约商户小程序 sub_appid;direct 为空',
    sub_app_secret_enc        VARCHAR(512) NULL COMMENT 'sub_app_id 对应的 appSecret 密文',
    login_app_source          VARCHAR(8)   NOT NULL DEFAULT 'app' COMMENT '登录 code2Session 用哪套凭据:app-用 app_id,sub-用 sub_app_id',
    api_v3_key_enc            VARCHAR(512) NOT NULL COMMENT 'APIv3 密钥(32 字节)密文,回调解密用',
    merchant_serial_no        VARCHAR(64)  NOT NULL COMMENT '商户 API 证书序列号,请求签名用',
    merchant_private_key_enc  TEXT         NOT NULL COMMENT '商户私钥(PKCS#8 PEM)密文',
    platform_serial_no        VARCHAR(64)  NULL COMMENT '微信平台证书序列号,回调验签用',
    platform_public_key_enc   TEXT         NULL COMMENT '微信平台证书公钥密文(证书或 SPKI PEM)',
    status                    INT          NOT NULL DEFAULT 1 COMMENT '0-停用 1-正常',
    remark                    VARCHAR(255) NULL,
    create_time               DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time               DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    create_by                 BIGINT       NULL,
    update_by                 BIGINT       NULL,
    UNIQUE KEY uk_tenant (tenant_id)
) COMMENT '微信支付渠道配置,平台管理,每租户一行';

-- ============================================================
-- 微信支付配置菜单(平台管理 id = 5 下)
--
-- is_platform = 1 的菜单不进任何套餐(5.2.1),所以租户角色永远拿不到这两个权限码。
-- 权限码必须落库:超管的权限码同样来自 sys_menu(见 PermissionProviderImpl)。
-- ============================================================
INSERT INTO sys_menu (id, parent_id, menu_name, menu_type, route_path, perm_code, icon, is_platform, sort_order, status) VALUES
    (10,  5, '微信支付配置', 2, 'wx-pay', NULL,                 'wallet', 1, 5, 1),
    (101, 10, '配置查询', 3, NULL,    'system:wxpay:list',      NULL,     1, 1, 1),
    (102, 10, '配置修改', 3, NULL,    'system:wxpay:update',    NULL,     1, 2, 1)
ON DUPLICATE KEY UPDATE menu_name = VALUES(menu_name),
                        parent_id = VALUES(parent_id),
                        route_path = VALUES(route_path),
                        perm_code = VALUES(perm_code),
                        icon = VALUES(icon),
                        is_platform = VALUES(is_platform),
                        sort_order = VALUES(sort_order),
                        status = VALUES(status);

-- ============================================================
-- 单体应用脚手架:RBAC + 多租户 建表 + 平台种子
-- 对应架构文档 architecture.md。商城业务表与商城种子在 V2__mall_init.sql ——
-- 两个脚本是一套:只跑本脚本能得到一个可用的后台(登录/租户/权限/字典),不跑 V2 就没有商城。
--
-- 全局约定(改动前先读架构文档 9.7 节):
--   1. 不使用外键约束,引用完整性由 service 层保证(见架构文档 5.6)
--   2. 统一 InnoDB + utf8mb4 + utf8mb4_0900_ai_ci
--   3. 时间列统一 DATETIME(不用 TIMESTAMP):Java 侧 LocalDateTime,业务时区 Asia/Shanghai
--   4. 业务数据主键一律应用层生成的雪花 ID;唯一例外是种子数据的固定小整数 ID
--      (后续脚本要能稳定引用它们,比如"把新菜单加进全量套餐";雪花 ID 每次生成都不同)
--   5. 每张租户表的 tenant_id 必须有索引(它是租户过滤条件的第一个字段)
--   6. 唯一约束显式声明在这里,不在应用层用"先查后插"保证并发安全
--   7. 标志位/枚举列统一用 INT,不用 TINYINT:Java 侧统一映射 Integer,而 Hibernate 的
--      ddl-auto=validate 会把 TINYINT 判为与 Integer 类型不匹配(要么全用 Byte,要么全用 INT)。
--      本方案选 INT,避免在每一处 DTO/实体边界做 Byte<->Integer 转换
--
-- 种子数据的两条硬性约定:
--   1. 幂等:全部使用 INSERT ... ON DUPLICATE KEY UPDATE(no-op),重复执行不产生重复数据,
--      也不像 INSERT IGNORE 那样把数据错误一起吞掉
--   2. 固定小整数 ID,理由见上面第 4 条
--
-- 平台超管初始账号:tenant_code = platform / admin / admin123
--   密码字段是真实可用的 BCrypt(cost 10)哈希,已置 must_change_password = 1,
--   首次登录会被强制改密。**上线前请务必确认这条数据没有被改动过。**
-- ============================================================

-- ============================================================
-- 租户
-- ============================================================
CREATE TABLE tenant (
    id              BIGINT PRIMARY KEY COMMENT '雪花ID,应用层生成',
    tenant_code     VARCHAR(32)  NOT NULL UNIQUE COMMENT '租户编码,登录时用来定位租户',
    tenant_name     VARCHAR(64)  NOT NULL,
    status          INT      NOT NULL DEFAULT 1 COMMENT '0-禁用 1-正常,禁用后在线会话按架构文档4.11失效',
    package_id      BIGINT       NULL COMMENT '套餐ID。为空表示"不限"(全部非平台菜单),仅兼容历史数据;建租户接口要求必填,见架构文档4.8',
    expire_time     DATETIME     NULL COMMENT '为空表示不过期',
    create_time     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    create_by       BIGINT       NULL,
    update_by       BIGINT       NULL
) COMMENT '租户表。注意:这里没有 is_super——"跳过租户过滤"是用户级标志,在 sys_user.is_super,见架构文档4.1/4.10';

-- ============================================================
-- 套餐:平台级数据,定义"一个租户能用哪些菜单/功能"(entitlement)
-- 对应架构文档 4.7 节。BaseAuditEntity,不分租户。
-- ============================================================
CREATE TABLE sys_package (
    id              BIGINT PRIMARY KEY COMMENT '雪花ID,应用层生成',
    package_name    VARCHAR(64)  NOT NULL,
    remark          VARCHAR(255) NULL,
    status          INT      NOT NULL DEFAULT 1 COMMENT '0-禁用 1-正常,禁用后不可再被新租户选用,不影响已绑定该套餐的租户',
    create_time     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    create_by       BIGINT       NULL,
    update_by       BIGINT       NULL,
    UNIQUE KEY uk_package_name (package_name)
) COMMENT '套餐表,全平台统一,不分租户';

CREATE TABLE sys_package_menu (
    package_id      BIGINT NOT NULL,
    menu_id         BIGINT NOT NULL,
    PRIMARY KEY (package_id, menu_id),
    KEY idx_menu (menu_id)
) COMMENT '套餐-菜单关联表。约定:①只能包含 sys_menu.is_platform = 0 的菜单;②菜单树父链必须完整,见架构文档5.2.1';

-- ============================================================
-- 套餐变更记录:见架构文档 4.8、4.8.1 节。
-- 每次套餐变更落一条记录(创建租户时的初始绑定除外),
-- 用于审计、支持"未同步租户"的排查与重跑、支持变更历史追溯。
-- ============================================================
CREATE TABLE sys_tenant_package_change (
    id              BIGINT PRIMARY KEY COMMENT '雪花ID,应用层生成',
    tenant_id       BIGINT       NOT NULL,
    trigger_type    INT      NOT NULL DEFAULT 1 COMMENT '触发源:1-租户换套餐(4.8) 2-平台修改套餐菜单(4.8.1)',
    old_package_id  BIGINT       NULL COMMENT 'NULL 表示"变更前不限";首次绑定时也为 NULL,靠 trigger_type 区分',
    new_package_id  BIGINT       NOT NULL COMMENT 'trigger_type=2 时与 old_package_id 相同',
    added_menu_ids  TEXT         NULL COMMENT '本次新增授权的菜单ID列表(仅默认管理员角色),JSON数组',
    revoked_menu_ids TEXT        NULL COMMENT '本次收回的菜单ID列表(该租户全部角色一并生效),JSON数组',
    create_time     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    update_by       BIGINT       NULL,
    create_by       BIGINT       NULL,
    KEY idx_tenant (tenant_id),
    KEY idx_tenant_time (tenant_id, create_time)
) COMMENT '套餐变更记录表,平台级审计数据,不分租户';

-- ============================================================
-- 组织架构
-- ============================================================
CREATE TABLE sys_dept (
    id              BIGINT PRIMARY KEY COMMENT '雪花ID,应用层生成',
    tenant_id       BIGINT       NOT NULL,
    parent_id       BIGINT       NOT NULL DEFAULT 0,
    ancestors       VARCHAR(255) NOT NULL DEFAULT '' COMMENT '祖级列表,逗号分隔,便于"本部门及以下"查询',
    dept_name       VARCHAR(64)  NOT NULL,
    sort_order      INT          NOT NULL DEFAULT 0,
    status          INT      NOT NULL DEFAULT 1,
    create_time     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    create_by       BIGINT       NULL,
    update_by       BIGINT       NULL,
    UNIQUE KEY uk_tenant_dept_name (tenant_id, dept_name),
    KEY idx_tenant (tenant_id),
    KEY idx_parent (parent_id)
) COMMENT '部门表。删除规则:有子部门或有关联用户时拒绝删除,见架构文档5.6';

-- ============================================================
-- 用户
-- ============================================================
CREATE TABLE sys_user (
    id              BIGINT PRIMARY KEY COMMENT '雪花ID,应用层生成',
    tenant_id       BIGINT       NOT NULL,
    dept_id         BIGINT       NULL,
    username        VARCHAR(64)  NOT NULL,
    password        VARCHAR(128) NOT NULL COMMENT 'BCrypt hash,任何接口与日志都不得输出该字段',
    nickname        VARCHAR(64)  NULL,
    phone           VARCHAR(20)  NULL,
    status          INT      NOT NULL DEFAULT 1 COMMENT '0-禁用 1-正常,禁用后在线会话按架构文档4.11失效',
    is_super        INT      NOT NULL DEFAULT 0 COMMENT '1-平台超管:跳过租户过滤、权限短路为全量,见架构文档4.10。只能由种子数据/运维脚本设置,接口不接受该字段',
    must_change_password INT NOT NULL DEFAULT 0 COMMENT '1-强制改密,除改密/登出/刷新权限外一律403,见架构文档7.1.2',
    pwd_update_time DATETIME     NULL COMMENT '最近一次改密时间',
    login_fail_count INT         NOT NULL DEFAULT 0,
    lock_time       DATETIME     NULL COMMENT '登录失败锁定截止时间',
    create_time     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    create_by       BIGINT       NULL,
    update_by       BIGINT       NULL,
    UNIQUE KEY uk_tenant_username (tenant_id, username),
    UNIQUE KEY uk_tenant_phone (tenant_id, phone),
    KEY idx_tenant (tenant_id),
    KEY idx_dept (dept_id)
) COMMENT '用户表。手机号可为空,空值不参与唯一约束(MySQL 唯一索引允许多个 NULL)';

-- ============================================================
-- 角色:tenant_id 始终非空,新租户的初始角色由套餐(见 sys_package)生成
-- ============================================================
CREATE TABLE sys_role (
    id              BIGINT PRIMARY KEY COMMENT '雪花ID,应用层生成',
    tenant_id       BIGINT       NOT NULL,
    role_key        VARCHAR(64)  NOT NULL COMMENT '角色标识,如 admin/finance',
    role_name       VARCHAR(64)  NOT NULL,
    data_scope      INT      NOT NULL DEFAULT 5
        COMMENT '数据权限范围: 1-仅本人 2-本部门 3-本部门及以下 4-自定义部门 5-全部;多角色时取最大值,见架构文档5.3',
    is_default      INT      NOT NULL DEFAULT 0
        COMMENT '1-租户创建时自动生成的默认管理员角色,套餐变更时只自动同步这个角色,见架构文档4.8节。一个租户内至多一条为1,由建租户流程保证,不建唯一索引(应用层保证即可,允许极端情况下人工修复)。该角色的菜单由套餐同步维护,不接受人工增删,也不允许删除',
    status          INT      NOT NULL DEFAULT 1,
    create_time     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    create_by       BIGINT       NULL,
    update_by       BIGINT       NULL,
    UNIQUE KEY uk_tenant_role_key (tenant_id, role_key),
    KEY idx_tenant (tenant_id),
    KEY idx_tenant_default (tenant_id, is_default)
) COMMENT '角色表';

CREATE TABLE sys_role_dept (
    role_id         BIGINT NOT NULL,
    dept_id         BIGINT NOT NULL,
    PRIMARY KEY (role_id, dept_id),
    KEY idx_dept (dept_id)
) COMMENT '数据权限=自定义部门 时使用';

-- ============================================================
-- 菜单/权限点:menu_type 区分目录/页面/按钮,perm_code 只有按钮级才需要
--   route_path 约定:目录给全路径(如 /system),页面给父级下的片段(如 user),
--   前端按这两条拼出完整路由;菜单树驱动动态路由见架构文档 7.4。
-- ============================================================
CREATE TABLE sys_menu (
    id              BIGINT PRIMARY KEY COMMENT '雪花ID,应用层生成',
    parent_id       BIGINT       NOT NULL DEFAULT 0,
    menu_name       VARCHAR(64)  NOT NULL,
    menu_type       INT      NOT NULL COMMENT '1-目录 2-页面 3-按钮',
    route_path      VARCHAR(128) NULL COMMENT 'menu_type=2 时使用',
    perm_code       VARCHAR(128) NULL COMMENT 'menu_type=3 时使用,如 order:delete。全局唯一,否则 @SaCheckPermission 语义失效',
    icon            VARCHAR(64)  NULL COMMENT '图标名,取值必须是前端 SideMenu 的 icons 表里登记过的名字(否则该菜单没有图标)',
    is_platform     INT      NOT NULL DEFAULT 0 COMMENT '1-平台专用菜单(套餐管理/租户管理等),不允许进入任何套餐,见架构文档4.10',
    sort_order      INT          NOT NULL DEFAULT 0,
    status          INT      NOT NULL DEFAULT 1,
    create_time     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    create_by       BIGINT       NULL,
    update_by       BIGINT       NULL,
    UNIQUE KEY uk_perm_code (perm_code),
    KEY idx_parent (parent_id)
) COMMENT '菜单/权限点表,全平台统一,不分租户。perm_code 可空(目录与页面没有),唯一索引允许多个 NULL';

-- ============================================================
-- 关联表
-- 注意:这三张表没有 tenant_id,不受任何自动租户过滤,隔离靠架构文档 4.6.1 的两条约束
-- ============================================================
CREATE TABLE sys_user_role (
    user_id         BIGINT NOT NULL,
    role_id         BIGINT NOT NULL,
    PRIMARY KEY (user_id, role_id),
    KEY idx_role (role_id)
);

CREATE TABLE sys_role_menu (
    role_id         BIGINT NOT NULL,
    menu_id         BIGINT NOT NULL,
    PRIMARY KEY (role_id, menu_id),
    KEY idx_menu (menu_id)
) COMMENT 'idx_menu 是必须的:套餐收回是按 menu_id 批量 delete,见架构文档9.7';

-- ============================================================
-- 操作日志:审计用,出问题第一个排查的地方。
-- tenant_id/user_id 允许为空:覆盖"登录失败"这类还没识别出用户的场景,
-- 不能因为非空约束把最有排查价值的一条日志丢掉(见架构文档7.2)。
-- 异步落库时这两列由 AuditContext 快照显式赋值(见架构文档4.12)。
-- ============================================================
CREATE TABLE sys_oper_log (
    id              BIGINT PRIMARY KEY COMMENT '雪花ID,应用层生成',
    tenant_id       BIGINT       NULL,
    user_id         BIGINT       NULL,
    module          VARCHAR(64)  NOT NULL,
    perm_code       VARCHAR(128) NULL,
    method          VARCHAR(255) NOT NULL,
    request_params  TEXT         NULL COMMENT '写入前必须按字段黑名单脱敏并截断(架构文档7.2),绝不允许落明文密码',
    status          INT      NOT NULL COMMENT '0-失败 1-成功',
    error_msg       TEXT         NULL COMMENT '截断到4000字符',
    ip              VARCHAR(64)  NULL,
    trace_id        VARCHAR(64)  NULL COMMENT '与 MDC/响应头 X-Trace-Id 对应,见架构文档7.2',
    create_time     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    create_by       BIGINT       NULL,
    update_by       BIGINT       NULL,
    KEY idx_tenant_time (tenant_id, create_time),
    KEY idx_user (user_id)
) COMMENT '操作日志表,异步落库,不阻塞主流程';

-- ============================================================
-- 字典:全局共用,不分租户
-- ============================================================
CREATE TABLE sys_dict_type (
    id              BIGINT PRIMARY KEY COMMENT '雪花ID,应用层生成',
    dict_type       VARCHAR(64)  NOT NULL UNIQUE,
    dict_name       VARCHAR(64)  NOT NULL,
    create_time     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    create_by       BIGINT       NULL,
    update_by       BIGINT       NULL
) COMMENT '字典类型表,平台级数据(BaseAuditEntity)';

CREATE TABLE sys_dict_data (
    id              BIGINT PRIMARY KEY COMMENT '雪花ID,应用层生成',
    dict_type       VARCHAR(64)  NOT NULL,
    dict_label      VARCHAR(64)  NOT NULL,
    dict_value      VARCHAR(64)  NOT NULL,
    sort_order      INT          NOT NULL DEFAULT 0,
    create_time     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    create_by       BIGINT       NULL,
    update_by       BIGINT       NULL,
    UNIQUE KEY uk_type_value (dict_type, dict_value),
    KEY idx_type (dict_type)
) COMMENT '字典数据表,平台级数据(BaseAuditEntity)';

-- ============================================================
-- 微信支付渠道配置(平台管理,每租户一行)
--
-- 密钥类列一律存信封加密后的密文(见 SecretCipher),明文只在内存中出现;
-- 主密钥来自配置项(没配则退化为明文并存启动告警),所以"库被读到"与"缓存被读到"的损失等价。
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
-- 种子数据
-- ============================================================
SET NAMES utf8mb4;

-- ------------------------------------------------------------
-- 平台租户:超管账号挂在这个租户下。它不参与业务数据的租户隔离判断
-- (能否跨租户看数据取决于 sys_user.is_super,不是租户,见架构文档 4.10)
-- ------------------------------------------------------------
INSERT INTO tenant (id, tenant_code, tenant_name, status, package_id, expire_time, create_by)
VALUES (1, 'platform', '平台', 1, 1, NULL, NULL)
ON DUPLICATE KEY UPDATE tenant_code = tenant_code;

-- 平台租户的根部门:data_scope = 2/3/4 依赖部门层级,没有根部门这些档位会退化成"看不到任何数据"
INSERT INTO sys_dept (id, tenant_id, parent_id, ancestors, dept_name, sort_order, status)
VALUES (1, 1, 0, '', '平台总部', 1, 1)
ON DUPLICATE KEY UPDATE dept_name = dept_name;

-- ------------------------------------------------------------
-- 套餐:全量套餐(供平台租户自己使用,也是新租户的默认选择对象)
-- ------------------------------------------------------------
INSERT INTO sys_package (id, package_name, remark, status)
VALUES (1, '全量套餐', '包含全部非平台专用菜单', 1)
ON DUPLICATE KEY UPDATE package_name = package_name;

-- ------------------------------------------------------------
-- 菜单树
--   is_platform = 1 的是平台管理菜单:不允许进入任何套餐(架构文档 5.2.1),
--   所以普通租户的授权候选集里永远不会出现它们 —— 这是平台级接口的第一层防线(4.10)。
--   perm_code 只挂在 button(menu_type = 3)上,与建表脚本的列注释保持一致。
--   icon 必须在 web/src/layout/SideMenu.vue 的 icons 表里有对应项,否则该菜单静默没有图标。
-- ------------------------------------------------------------
INSERT INTO sys_menu (id, parent_id, menu_name, menu_type, route_path, perm_code, icon, is_platform, sort_order, status) VALUES
    -- 系统管理(非平台菜单,进全量套餐)
    (1,  0, '系统管理', 1, '/system',   NULL,                          'settings', 0, 1, 1),
    (2,  1, '用户管理', 2, 'user',      NULL,                          'people',   0, 1, 1),
    (11, 2, '用户列表', 3, NULL,        'system:user:list',            NULL,       0, 1, 1),
    (12, 2, '用户详情', 3, NULL,        'system:user:query',           NULL,       0, 2, 1),
    (13, 2, '用户新增', 3, NULL,        'system:user:create',          NULL,       0, 3, 1),
    (14, 2, '用户修改', 3, NULL,        'system:user:update',          NULL,       0, 4, 1),
    (15, 2, '用户删除', 3, NULL,        'system:user:delete',          NULL,       0, 5, 1),
    (16, 2, '用户启停', 3, NULL,        'system:user:status',          NULL,       0, 6, 1),
    (17, 2, '重置密码', 3, NULL,        'system:user:reset-password',  NULL,       0, 7, 1),
    (18, 2, '解除锁定', 3, NULL,        'system:user:unlock',          NULL,       0, 8, 1),
    -- 部门没有独立页面(已合并进用户管理页的左侧树),但权限码仍在使用,所以按钮直接挂用户管理下
    (31, 2, '部门列表', 3, NULL,        'system:dept:list',            NULL,       0, 9, 1),
    (32, 2, '部门新增', 3, NULL,        'system:dept:create',          NULL,       0, 10, 1),
    (33, 2, '部门修改', 3, NULL,        'system:dept:update',          NULL,       0, 11, 1),
    (34, 2, '部门删除', 3, NULL,        'system:dept:delete',          NULL,       0, 12, 1),
    (3,  1, '角色管理', 2, 'role',      NULL,                          'shield',   0, 2, 1),
    (21, 3, '角色列表', 3, NULL,        'system:role:list',            NULL,       0, 1, 1),
    (22, 3, '角色新增', 3, NULL,        'system:role:create',          NULL,       0, 2, 1),
    (23, 3, '角色修改', 3, NULL,        'system:role:update',          NULL,       0, 3, 1),
    (24, 3, '角色删除', 3, NULL,        'system:role:delete',          NULL,       0, 4, 1),
    (25, 3, '角色启停', 3, NULL,        'system:role:status',          NULL,       0, 5, 1),
    (26, 3, '角色授权', 3, NULL,        'system:role:grant',           NULL,       0, 6, 1),
    -- 平台管理(平台专用:不进任何套餐,租户角色拿不到这些权限码)
    (5,  0, '平台管理', 1, '/platform', NULL,                          'crown',    1, 9, 1),
    (6,  5, '租户管理', 2, 'tenant',    NULL,                          'shop',     1, 1, 1),
    (41, 6, '租户列表', 3, NULL,        'system:tenant:list',          NULL,       1, 1, 1),
    (42, 6, '租户新增', 3, NULL,        'system:tenant:create',        NULL,       1, 2, 1),
    (43, 6, '租户换套餐', 3, NULL,      'system:tenant:package',       NULL,       1, 3, 1),
    (44, 6, '租户启停', 3, NULL,        'system:tenant:status',        NULL,       1, 4, 1),
    (7,  5, '套餐管理', 2, 'package',   NULL,                          'gift',     1, 2, 1),
    (51, 7, '套餐列表', 3, NULL,        'system:package:list',         NULL,       1, 1, 1),
    (52, 7, '套餐新增', 3, NULL,        'system:package:create',       NULL,       1, 2, 1),
    (53, 7, '套餐修改', 3, NULL,        'system:package:update',       NULL,       1, 3, 1),
    (8,  5, '菜单管理', 2, 'menu',      NULL,                          'list',     1, 3, 1),
    (61, 8, '菜单列表', 3, NULL,        'system:menu:list',            NULL,       1, 1, 1),
    (62, 8, '菜单新增', 3, NULL,        'system:menu:create',          NULL,       1, 2, 1),
    (63, 8, '菜单修改', 3, NULL,        'system:menu:update',          NULL,       1, 3, 1),
    (64, 8, '菜单删除', 3, NULL,        'system:menu:delete',          NULL,       1, 4, 1),
    -- 字典与微信支付配置是平台级表(4.6.1),维护入口也必须是平台专用菜单
    (9,  5, '字典管理', 2, 'dict',      NULL,                          'book',     1, 4, 1),
    (91, 9, '字典列表', 3, NULL,        'system:dict:list',            NULL,       1, 1, 1),
    (92, 9, '字典新增', 3, NULL,        'system:dict:create',          NULL,       1, 2, 1),
    (93, 9, '字典修改', 3, NULL,        'system:dict:update',          NULL,       1, 3, 1),
    (94, 9, '字典删除', 3, NULL,        'system:dict:delete',          NULL,       1, 4, 1),
    (10, 5, '微信支付配置', 2, 'wx-pay', NULL,                         'wallet',   1, 5, 1),
    (101, 10, '配置查询', 3, NULL,      'system:wxpay:list',           NULL,       1, 1, 1),
    (102, 10, '配置修改', 3, NULL,      'system:wxpay:update',         NULL,       1, 2, 1)
ON DUPLICATE KEY UPDATE menu_name = VALUES(menu_name),
                        parent_id = VALUES(parent_id),
                        menu_type = VALUES(menu_type),
                        route_path = VALUES(route_path),
                        perm_code = VALUES(perm_code),
                        icon = VALUES(icon),
                        is_platform = VALUES(is_platform),
                        sort_order = VALUES(sort_order),
                        status = VALUES(status);

-- ------------------------------------------------------------
-- 平台管理员角色 + 授予全部菜单(含平台专用菜单)
-- 超管的权限不走角色(StpInterface 对 is_super = 1 短路返回全量,见 4.10),
-- 这里仍然绑定,是为了让"平台租户下的普通运营账号"将来可以按角色授权。
-- ------------------------------------------------------------
INSERT INTO sys_role (id, tenant_id, role_key, role_name, data_scope, is_default, status)
VALUES (1, 1, 'platform_admin', '平台管理员', 5, 1, 1)
ON DUPLICATE KEY UPDATE role_name = role_name;

INSERT INTO sys_role_menu (role_id, menu_id)
SELECT 1, id FROM sys_menu
ON DUPLICATE KEY UPDATE menu_id = menu_id;

-- ------------------------------------------------------------
-- 全量套餐的菜单 = 全部非平台专用菜单。
-- 用 SELECT 而不是硬编码列表:后续新增业务菜单时,只要 is_platform = 0 就会自动落到全量套餐里
-- (V2__mall_init.sql 同理再跑一次,把商城菜单补进来)。
-- ------------------------------------------------------------
INSERT INTO sys_package_menu (package_id, menu_id)
SELECT 1, id FROM sys_menu WHERE is_platform = 0
ON DUPLICATE KEY UPDATE menu_id = menu_id;

-- ------------------------------------------------------------
-- 平台超管账号
-- 密码:admin123(BCrypt cost 10)。上线前必须确认此值,并在首次登录后立即修改。
-- ------------------------------------------------------------
INSERT INTO sys_user (id, tenant_id, dept_id, username, password, nickname, status, is_super, must_change_password)
VALUES (1, 1, 1, 'admin', '$2a$10$olnYwrHwQDwFgYWeK7Q.tOrN0WviMndlIg3rN5LRZLk0jm2DlgIj6', '平台超管', 1, 1, 1)
ON DUPLICATE KEY UPDATE is_super = VALUES(is_super),
                        must_change_password = VALUES(must_change_password);

INSERT INTO sys_user_role (user_id, role_id) VALUES (1, 1)
ON DUPLICATE KEY UPDATE role_id = role_id;

-- ------------------------------------------------------------
-- 基础字典
-- ------------------------------------------------------------
INSERT INTO sys_dict_type (id, dict_type, dict_name) VALUES
    (1, 'sys_common_status', '通用状态'),
    (2, 'sys_menu_type', '菜单类型')
ON DUPLICATE KEY UPDATE dict_name = VALUES(dict_name);

INSERT INTO sys_dict_data (id, dict_type, dict_label, dict_value, sort_order) VALUES
    (1, 'sys_common_status', '正常', '1', 1),
    (2, 'sys_common_status', '停用', '0', 2),
    (3, 'sys_menu_type', '目录', '1', 1),
    (4, 'sys_menu_type', '页面', '2', 2),
    (5, 'sys_menu_type', '按钮', '3', 3)
ON DUPLICATE KEY UPDATE dict_label = VALUES(dict_label),
                        dict_value = VALUES(dict_value),
                        sort_order = VALUES(sort_order);

-- ============================================================
-- 单体应用脚手架:RBAC + 多租户 建表脚本
-- 对应架构文档 architecture.md,作为 Flyway 的 V1__init_schema.sql
--
-- 全局约定(改动前先读架构文档 9.7 节):
--   1. 不使用外键约束,引用完整性由 service 层保证(见架构文档 5.6)
--   2. 统一 InnoDB + utf8mb4 + utf8mb4_0900_ai_ci
--   3. 时间列统一 DATETIME(不用 TIMESTAMP):Java 侧 LocalDateTime,业务时区 Asia/Shanghai
--   4. 业务数据主键一律应用层生成的雪花 ID;唯一例外是 V2 种子数据的固定小整数 ID
--   5. 每张租户表的 tenant_id 必须有索引(它是租户过滤条件的第一个字段)
--   6. 唯一约束显式声明在这里,不在应用层用"先查后插"保证并发安全
--   7. 标志位/枚举列统一用 INT,不用 TINYINT:Java 侧统一映射 Integer,而 Hibernate 的
--      ddl-auto=validate 会把 TINYINT 判为与 Integer 类型不匹配(要么全用 Byte,要么全用 INT)。
--      本方案选 INT,避免在每一处 DTO/实体边界做 Byte<->Integer 转换
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
-- 支付账号 → 租户映射:平台级数据,不分租户。
-- 用途见架构文档 6.1:当商户订单号格式被渠道定死、无法把 tenant_id 编码进去时的
-- 备选租户识别方案。它不是租户表,"账号→租户"本来就该有租户维度,查它不算绕过隔离。
-- ============================================================
CREATE TABLE sys_pay_account (
    id              BIGINT PRIMARY KEY COMMENT '雪花ID,应用层生成',
    channel         VARCHAR(32)  NOT NULL COMMENT '渠道标识,如 wx/alipay',
    pay_account_id  VARCHAR(64)  NOT NULL COMMENT '渠道侧账号标识,如商户号 mch_id',
    tenant_id       BIGINT       NOT NULL COMMENT '该支付账号归属的租户',
    status          INT      NOT NULL DEFAULT 1 COMMENT '0-停用 1-正常',
    create_time     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    create_by       BIGINT       NULL,
    update_by       BIGINT       NULL,
    UNIQUE KEY uk_channel_account (channel, pay_account_id),
    KEY idx_tenant (tenant_id)
) COMMENT '支付账号与租户的映射表,平台级';

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
-- ============================================================
CREATE TABLE sys_menu (
    id              BIGINT PRIMARY KEY COMMENT '雪花ID,应用层生成',
    parent_id       BIGINT       NOT NULL DEFAULT 0,
    menu_name       VARCHAR(64)  NOT NULL,
    menu_type       INT      NOT NULL COMMENT '1-目录 2-页面 3-按钮',
    route_path      VARCHAR(128) NULL COMMENT 'menu_type=2 时使用',
    perm_code       VARCHAR(128) NULL COMMENT 'menu_type=3 时使用,如 order:delete。全局唯一,否则 @SaCheckPermission 语义失效',
    icon            VARCHAR(64)  NULL,
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

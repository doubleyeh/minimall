package com.minimall.domain.sys;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * 菜单/权限点(架构文档 5.1、5.2):全平台统一,不分租户,租户角色只能勾选授权、不能自建。
 *
 * <p>字段语义:
 * <ul>
 *   <li>{@code menuType}:1-目录 2-页面 3-按钮。{@code permCode} 只有按钮级才有</li>
 *   <li>{@code permCode}:格式 {@code 模块:资源:操作}(如 {@code order:delete}),**全局唯一**
 *       (建表脚本 uk_perm_code)。不唯一会让 {@code @SaCheckPermission} 的语义失效——
 *       两个功能共用一个权限码时,授权其中一个等于顺带授权另一个</li>
 *   <li>{@code isPlatform}:1 表示平台专用菜单(套餐管理/租户管理等),**不允许进入任何套餐**(5.2.1)。
 *       这是平台级接口的第一层防线:普通租户的授权候选集里根本取不到它(见 4.10)</li>
 * </ul>
 *
 * <p>平台级实体,不走租户过滤,只有超管能维护。
 */
@Entity
@Table(name = "sys_menu")
@Getter
@Setter
public class SysMenu extends BaseAuditEntity {

    public static final int TYPE_DIRECTORY = 1;
    public static final int TYPE_PAGE = 2;
    public static final int TYPE_BUTTON = 3;

    @Column(name = "parent_id", nullable = false)
    private Long parentId;

    @Column(name = "menu_name", nullable = false, length = 64)
    private String menuName;

    @Column(name = "menu_type", nullable = false)
    private Integer menuType;

    @Column(name = "route_path", length = 128)
    private String routePath;

    @Column(name = "perm_code", length = 128)
    private String permCode;

    @Column(name = "icon", length = 64)
    private String icon;

    @Column(name = "is_platform", nullable = false)
    private Integer isPlatform;

    @Column(name = "sort_order", nullable = false)
    private Integer sortOrder;

    @Column(name = "status", nullable = false)
    private Integer status;

    public boolean isDirectory() {
        return menuType != null && menuType == TYPE_DIRECTORY;
    }

    public boolean isPage() {
        return menuType != null && menuType == TYPE_PAGE;
    }

    public boolean isButton() {
        return menuType != null && menuType == TYPE_BUTTON;
    }

    public boolean isPlatformOnly() {
        return isPlatform != null && isPlatform == 1;
    }
}

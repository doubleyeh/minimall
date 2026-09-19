package com.minimall.sys.domain;

import com.minimall.infra.persistence.BaseAuditEntity;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.util.HashSet;
import java.util.Set;

/**
 * 套餐(架构文档 4.7):平台级数据,定义"这个租户能用哪些菜单/功能"(entitlement 天花板)。
 *
 * <p>{@code menuIds} 映射到 {@code sys_package_menu}。为什么用 {@code @ElementCollection} 而不是
 * 给关联表建实体:{@code sys_package_menu} 只有 (package_id, menu_id) 两个字段、没有业务属性,
 * 建实体还要额外写一个复合主键类,纯属噪音;{@code @ElementCollection} 让增删元素直接落到
 * 关联表的 insert/delete 上,和"这个套餐包含哪些菜单"的语义完全对应。
 *
 * <p>注意:**读这个集合不会自动带上租户过滤**(关联表没有 tenant_id),
 * 安全性来自"只能通过已被过滤的实体访问它"(见 4.6.1 对三张关联表的说明)。
 */
@Entity
@Table(name = "sys_package")
@Getter
@Setter
public class SysPackage extends BaseAuditEntity {

    @Column(name = "package_name", nullable = false, length = 64)
    private String packageName;

    @Column(name = "remark", length = 255)
    private String remark;

    /** 0-禁用 1-正常。禁用后不可再被新租户选用,不影响已绑定租户 */
    @Column(name = "status", nullable = false)
    private Integer status;

    /**
     * 套餐包含的菜单 ID。
     *
     * <p>约定(见 5.2.1):只能是 {@code is_platform = 0} 的菜单,且菜单树父链必须完整。
     * 这两条是服务层校验的,数据库没有约束(没有外键,见 9.7)。
     */
    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(name = "sys_package_menu", joinColumns = @JoinColumn(name = "package_id"))
    @Column(name = "menu_id", nullable = false)
    private Set<Long> menuIds = new HashSet<>();
}

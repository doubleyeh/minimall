package com.minimall.domain.sys;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * 字典数据(架构文档 5.1):平台级数据,全局共用、不分租户。
 *
 * <p>不参与数据权限(4.6.1)——字典是配置数据,没有部门归属的语义。
 */
@Entity
@Table(name = "sys_dict_data")
@Getter
@Setter
public class SysDictData extends BaseAuditEntity {

    @Column(name = "dict_type", nullable = false, length = 64)
    private String dictType;

    @Column(name = "dict_label", nullable = false, length = 64)
    private String dictLabel;

    @Column(name = "dict_value", nullable = false, length = 64)
    private String dictValue;

    @Column(name = "sort_order", nullable = false)
    private Integer sortOrder;
}

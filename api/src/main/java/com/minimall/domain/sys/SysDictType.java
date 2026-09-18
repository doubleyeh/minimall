package com.minimall.domain.sys;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * 字典类型(架构文档 5.1):平台级数据,全局共用、不分租户。
 */
@Entity
@Table(name = "sys_dict_type")
@Getter
@Setter
public class SysDictType extends BaseAuditEntity {

    @Column(name = "dict_type", nullable = false, length = 64)
    private String dictType;

    @Column(name = "dict_name", nullable = false, length = 64)
    private String dictName;
}

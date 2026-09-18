package com.minimall.api.sys.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;

/**
 * 创建租户请求(架构文档 4.7 的建租户流程:租户 + 默认管理员角色 + 套餐菜单 + 根部门 + 管理员用户,一个事务)。
 */
public record TenantCreateRequest(

        @NotBlank(message = "租户编码不能为空")
        @Pattern(regexp = "^[a-z][a-z0-9-]{1,31}$", message = "租户编码需为2-32位小写字母/数字/短横线,且以字母开头")
        String tenantCode,

        @NotBlank(message = "租户名称不能为空")
        @Size(max = 64, message = "租户名称长度不能超过64")
        String tenantName,

        /** 套餐必填:不接受创建为 NULL("不限"只是兼容历史数据的路径,见架构文档 4.8 步骤 1) */
        @NotNull(message = "套餐不能为空")
        Long packageId,

        /** 为空表示不过期 */
        LocalDateTime expireTime,

        @NotBlank(message = "管理员用户名不能为空")
        @Pattern(regexp = "^[a-zA-Z][a-zA-Z0-9_]{2,63}$", message = "管理员用户名需为3-64位字母/数字/下划线,且以字母开头")
        String adminUsername,

        @Size(max = 64, message = "管理员昵称长度不能超过64")
        String adminNickname,

        /**
         * 初始密码:不传则由系统随机生成,并在创建响应里返回一次明文,
         * 同时置 must_change_password = 1(架构文档 7.1.2)。
         */
        @Size(min = 8, max = 32, message = "初始密码长度需为8-32位")
        String adminPassword
) {
}

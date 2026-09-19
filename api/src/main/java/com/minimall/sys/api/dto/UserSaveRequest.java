package com.minimall.sys.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 新增/修改用户请求。
 *
 * <p>为什么共用一个对象:新增与修改的字段集合几乎一致,拆两个 record 只会带来重复的校验注解。
 * 两个字段只在新增时有意义,修改时服务端忽略(见下)。
 *
 * <ul>
 *   <li>{@code username}:全局唯一性是 {@code (tenant_id, username)}(建表脚本 uk_tenant_username),
 *       **修改时不允许改**——它可能已经被审计日志、外部系统引用</li>
 *   <li>{@code password}:新增时不传则由系统随机生成并回传一次(置 must_change_password=1);
 *       修改时忽略,改密走 {@code POST /auth/password},重置走 {@code POST /users/{id}/password/reset}</li>
 * </ul>
 *
 * <p>注意:{@code roleIds} 直接决定用户的权限并集,而数据权限(5.3)在多角色时**取最大值(最宽)**,
 * 所以给用户挂角色时要注意宽窄方向。
 */
public record UserSaveRequest(

        @NotBlank(message = "用户名不能为空")
        @Pattern(regexp = "^[a-zA-Z][a-zA-Z0-9_]{2,63}$", message = "用户名需为3-64位字母/数字/下划线,且以字母开头")
        String username,

        @Size(min = 8, max = 32, message = "密码长度需为8-32位")
        String password,

        @Size(max = 64, message = "昵称长度不能超过64")
        String nickname,

        /** 可为空;同一租户内不允许重复(uk_tenant_phone) */
        @Pattern(regexp = "^$|^1[3-9]\\d{9}$", message = "手机号格式不正确")
        String phone,

        /** 可为空;为空用户按 data_scope = 2/3/4 查询时会命中空集(架构文档 5.3) */
        Long deptId,

        List<Long> roleIds,

        Integer status
) {
}

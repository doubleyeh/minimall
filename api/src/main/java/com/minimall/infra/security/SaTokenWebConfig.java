package com.minimall.infra.security;

import cn.dev33.satoken.interceptor.SaInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 让 {@code @SaCheckPermission} / {@code @SaCheckRole} / {@code @SaCheckLogin} 这些注解真正生效。
 *
 * <p><b>为什么必须有这个类</b>:Sa-Token 的注解鉴权不是 Spring 自带的机制,它由 Sa-Token 的拦截器
 * ({@link SaInterceptor})在进入 Controller 之前扫描 HandlerMethod 上的注解来完成。
 * 只写注解、不注册拦截器,注解就只是一行注释 —— 接口照旧返回 200,任何登录用户都能调用它。
 * 这不是理论风险:权限矩阵用例里"没有角色列表权限"的用户确实拿到了 200(见 8.2),
 * 补上这个拦截器之后同一请求返回 403。
 *
 * <p><b>刻意只做注解鉴权</b>,不设全局登录校验:全局 401 由
 * {@link com.minimall.infra.tenant.TenantWebFilter} 负责(它要在同一个位置完成租户识别、
 * 租户状态校验、强制改密拦截与审计快照)。同一件事留两处实现,早晚会出现"两处规则不一致"。
 */
@Configuration
public class SaTokenWebConfig implements WebMvcConfigurer {

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // 不传 handler 的构造方式表示"只做注解鉴权",不追加全局校验逻辑
        registry.addInterceptor(new SaInterceptor()).addPathPatterns("/**");
    }
}

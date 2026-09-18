package com.minimall.infra.web;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.filter.RequestContextFilter;

/**
 * 让 Spring 的请求上下文({@code RequestContextHolder})在 servlet filter 阶段就可用。
 *
 * <p>为什么需要它:{@code RequestContextHolder} 平时由 DispatcherServlet 在处理请求时才设置,
 * 而 filter 链远早于它。任何需要在 filter 阶段访问"当前请求"的代码
 * (Spring 的 request/session 作用域 Bean、{@code SpringMVCUtil} 这类工具)都依赖它。
 *
 * <p>顺序放在 {@link TraceIdFilter} 之后、很靠前的位置,让后续所有 filter 都能拿到请求上下文。
 * DispatcherServlet 稍后会再次设置/还原它,不会互相破坏(Spring 会保存并恢复外层值)。
 *
 * <p><b>注意区分</b>:Sa-Token 在 filter 阶段的上下文**不是**靠这里生效的,
 * 而是靠 {@code SaTokenContextFilterForJakartaServlet}(sa-token 以 order -104 注册)。
 * 详见 {@link com.minimall.infra.tenant.TenantWebFilter#ORDER}。
 */
@Configuration
public class WebContextConfig {

    @Bean
    public FilterRegistrationBean<RequestContextFilter> requestContextFilter() {
        FilterRegistrationBean<RequestContextFilter> registration = new FilterRegistrationBean<>(new RequestContextFilter());
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 1);
        return registration;
    }
}

package com.minimall.infra.tenant;

/**
 * 数据权限参数提供者(架构文档 5.3)。
 *
 * <p>为什么抽成接口:参数的计算需要查库(用户的多角色、所在部门、部门祖级链),
 * 而过滤服务必须保持"不依赖 HTTP、可以在测试里直接调用"(4.2)。接口留在 infra,
 * 实现放在 service/domain 侧,过滤服务只依赖这个接口。
 */
public interface DataScopeProvider {

    /**
     * 计算当前上下文的数据权限参数。
     *
     * <p>返回 {@link DataScopeParams#denyAll()} 表示"看不到任何数据"——
     * 这是**默认行为**:算不出参数时宁可返回空集,也不要退化成"不加条件"(那等于越权)。
     */
    DataScopeParams current();
}

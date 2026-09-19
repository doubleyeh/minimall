/**
 * 商城仓储层。
 *
 * <p><b>这里的每个仓储都必须覆盖 {@code findById}(走 QueryDSL 查询而不是主键直查)</b>。
 * 原因是 4.2 那条安全约束:所有 {@code mall_*} 实体都继承 {@code BaseTenantEntity},
 * 身上挂着租户过滤器,而 Hibernate 的 {@code @Filter} <b>只作用于查询</b>,
 * 对 {@code EntityManager.find()}(即 Spring Data 默认的 {@code findById})不生效 ——
 * 默认实现生成的 {@code select ... where id = ?} 不带任何租户条件,
 * 于是任何租户拿到一个 ID 就能读到/改到别的租户的行。
 *
 * <p>这条约束由 {@code MallRepositoryConventionTest} 用架构规则固化:新增仓储忘了覆盖,
 * 构建就会失败,而不是等到某次越权事故才被发现。
 */
package com.minimall.mall.domain.repository;

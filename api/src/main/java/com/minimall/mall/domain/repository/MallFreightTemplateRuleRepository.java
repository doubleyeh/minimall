package com.minimall.mall.domain.repository;

import com.minimall.mall.domain.MallFreightTemplateRule;
import com.minimall.mall.domain.QMallFreightTemplateRule;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.querydsl.QuerydslPredicateExecutor;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * 运费计费规则仓储。{@code findById} 必须走查询,原因见本包 {@code package-info}。
 */
public interface MallFreightTemplateRuleRepository extends JpaRepository<MallFreightTemplateRule, Long>,
        QuerydslPredicateExecutor<MallFreightTemplateRule> {

    @Override
    default Optional<MallFreightTemplateRule> findById(Long id) {
        return findOne(QMallFreightTemplateRule.mallFreightTemplateRule.id.eq(id));
    }

    List<MallFreightTemplateRule> findByTemplateIdOrderByIdAsc(Long templateId);

    /** 结算时一次取多个模板的规则,避免按模板逐个查(一单可能有多个模板分组,3.7)。 */
    List<MallFreightTemplateRule> findByTemplateIdInOrderByIdAsc(Collection<Long> templateIds);

    boolean existsByTemplateIdAndRegion(Long templateId, String region);

    long deleteByTemplateId(Long templateId);
}

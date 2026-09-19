package com.minimall.mall.domain.repository;

import com.minimall.mall.domain.MallFreightTemplate;
import com.minimall.mall.domain.QMallFreightTemplate;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.querydsl.QuerydslPredicateExecutor;

import java.util.List;
import java.util.Optional;

/**
 * 运费模板仓储。{@code findById} 必须走查询,原因见本包 {@code package-info}。
 */
public interface MallFreightTemplateRepository extends JpaRepository<MallFreightTemplate, Long>,
        QuerydslPredicateExecutor<MallFreightTemplate> {

    @Override
    default Optional<MallFreightTemplate> findById(Long id) {
        return findOne(QMallFreightTemplate.mallFreightTemplate.id.eq(id));
    }

    List<MallFreightTemplate> findByOrderByIdAsc();

    boolean existsByTemplateName(String templateName);

    boolean existsByTemplateNameAndIdNot(String templateName, Long id);
}

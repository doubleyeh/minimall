package com.minimall.mall.domain.repository;

import com.minimall.mall.domain.MallStockLog;
import com.minimall.mall.domain.QMallStockLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.querydsl.QuerydslPredicateExecutor;

import java.util.List;
import java.util.Optional;

/**
 * 库存流水仓储。{@code findById} 必须走查询,原因见本包 {@code package-info}。
 *
 * <p>流水只追加**(append-only)**:不提供任何更新/删除的业务方法(仓储继承了
 * {@code JpaRepository} 的 {@code delete} 能力,那是给测试清理数据用的)。
 */
public interface MallStockLogRepository extends JpaRepository<MallStockLog, Long>,
        QuerydslPredicateExecutor<MallStockLog> {

    @Override
    default Optional<MallStockLog> findById(Long id) {
        return findOne(QMallStockLog.mallStockLog.id.eq(id));
    }

    List<MallStockLog> findBySkuIdOrderByIdDesc(Long skuId);

    List<MallStockLog> findByBizIdOrderByIdDesc(Long bizId);
}

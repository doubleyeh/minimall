package com.minimall.mall.domain.repository;

import com.minimall.mall.domain.MallCustomer;
import com.minimall.mall.domain.QMallCustomer;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.querydsl.QuerydslPredicateExecutor;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

/**
 * 商城客户仓储。{@code findById} 必须走查询,原因见本包 {@code package-info}。
 *
 * <p>积分的增减一律用下面的条件 UPDATE 做**相对增减**,不要"读出来改再存":
 * 相对增减由数据库保证原子,而按读到的值写回会在并发时丢失另一笔变动。
 * 判断条件写进 {@code WHERE},受影响行数为 0 即表示余额不足。
 */
public interface MallCustomerRepository extends JpaRepository<MallCustomer, Long>,
        QuerydslPredicateExecutor<MallCustomer> {

    @Override
    default Optional<MallCustomer> findById(Long id) {
        return findOne(QMallCustomer.mallCustomer.id.eq(id));
    }

    /**
     * 按 (租户, openid) 定位客户 —— 微信登录的第一步(3.1)。
     *
     * <p>方法名里显式带上 tenantId:登录时租户刚从请求里解析出来,`TenantContext` 与过滤器
     * 可能还没来得及绑定新值,靠方法参数比靠上下文更可靠(与登录查用户同理)。
     */
    Optional<MallCustomer> findByTenantIdAndOpenid(Long tenantId, String openid);

    long countByMemberLevelId(Long memberLevelId);

    /** 增加积分(发放、退回)。 */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update MallCustomer c set c.points = c.points + :points "
            + "where c.id = :customerId and c.tenantId = :tenantId")
    int addPoints(@Param("customerId") Long customerId, @Param("tenantId") Long tenantId,
                  @Param("points") int points);

    /**
     * 扣减积分(抵现预扣)。
     *
     * @return 受影响行数;0 表示余额不足(被并发用掉),调用方应让用户刷新后重试
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update MallCustomer c set c.points = c.points - :points "
            + "where c.id = :customerId and c.tenantId = :tenantId and c.points >= :points")
    int deductPoints(@Param("customerId") Long customerId, @Param("tenantId") Long tenantId,
                     @Param("points") int points);

    /**
     * 扣减积分但**扣到 0 为止**(过期清零、退款扣回):余额不足时不报错、不欠账。
     *
     * <p>用 case when 而不是 {@code points - :points}:后者会把余额扣成负数。
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update MallCustomer c set c.points = case when c.points > :points then c.points - :points else 0 end "
            + "where c.id = :customerId and c.tenantId = :tenantId")
    int deductPointsClampToZero(@Param("customerId") Long customerId, @Param("tenantId") Long tenantId,
                                @Param("points") int points);

    /** 扣减成长值,同样扣到 0 为止。 */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update MallCustomer c set c.growthValue = "
            + "case when c.growthValue > :growth then c.growthValue - :growth else 0 end "
            + "where c.id = :customerId and c.tenantId = :tenantId")
    int deductGrowthClampToZero(@Param("customerId") Long customerId, @Param("tenantId") Long tenantId,
                                @Param("growth") int growth);
}

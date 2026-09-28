package com.minimall.mall.service;

import com.minimall.common.BusinessException;
import com.minimall.infra.id.SnowflakeIdGenerator;
import com.minimall.mall.domain.MallCustomer;
import com.minimall.mall.domain.MallMemberLevel;
import com.minimall.mall.domain.MallPointsBatch;
import com.minimall.mall.domain.MallPointsLog;
import com.minimall.mall.domain.repository.MallMemberLevelRepository;
import com.minimall.mall.domain.repository.MallPointsBatchRepository;
import com.minimall.mall.domain.repository.MallPointsLogRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 积分与成长值核心算法的集成测试(商城设计文档 3.4)。
 *
 * <p>直接调 service 而不是走下单/售后入口:这一层要独立证明"批次 FIFO、过期、滚动成长值"
 * 这几件事本身是对的,混在订单流程里出了问题分不清是谁的错。订单与售后的接入各有单独的用例。
 *
 * <p>清理放在 {@code @AfterEach}:这些表没有外键约束,留下的孤儿数据会让
 * {@code refreshRollingGrowth} 的"重算了多少客户"这类计数断言变得不稳定。
 */
class MemberPointsGrowthIntegrationTest extends MallClientServiceTestBase {

    @Autowired
    private MemberPointsService memberPointsService;
    @Autowired
    private MallPointsBatchRepository batchRepository;
    @Autowired
    private MallPointsLogRepository pointsLogRepository;
    @Autowired
    private MallMemberLevelRepository levelRepository;

    /** 造出来的订单 ID:这些表没有外键,用它把占用记录清干净。 */
    private final List<Long> createdOrderIds = new ArrayList<>();
    /** 造出来的等级定义。 */
    private final List<Long> createdLevelIds = new ArrayList<>();

    @AfterEach
    void tearDownMemberData() {
        inTenant(() -> {
            for (Long orderId : createdOrderIds) {
                jdbcTemplate.update("delete from mall_points_use where tenant_id = ? and order_id = ?",
                        TENANT_ID, orderId);
            }
            for (Long id : new Long[] { customerId, otherCustomerId }) {
                if (id == null) {
                    continue;
                }
                jdbcTemplate.update("delete from mall_points_batch where tenant_id = ? and customer_id = ?",
                        TENANT_ID, id);
                jdbcTemplate.update("delete from mall_points_log where tenant_id = ? and customer_id = ?",
                        TENANT_ID, id);
                jdbcTemplate.update("delete from mall_growth_log where tenant_id = ? and customer_id = ?",
                        TENANT_ID, id);
            }
            for (Long levelId : createdLevelIds) {
                levelRepository.findById(levelId).ifPresent(levelRepository::delete);
            }
            return null;
        });
    }

    // ---------------------------------------------------------------- 发放

    @Test
    @DisplayName("确认收货发放:按实付向下取整,同时给积分与成长值,并建一个批次")
    void grantIssuesPointsGrowthAndBatch() {
        int granted = inTenant(() -> memberPointsService.grant(customerId, newOrderId(), new BigDecimal("90.50")));

        assertThat(granted).as("90.50 元向下取整为 90").isEqualTo(90);
        assertThat(pointsOf(customerId)).isEqualTo(90);
        assertThat(growthOf(customerId)).isEqualTo(90);
        assertThat(usableBatchSum(customerId)).as("批次剩余必须与余额一致").isEqualTo(90);

        List<MallPointsLog> logs = inTenant(() -> pointsLogRepository.findByCustomerIdOrderByIdDesc(customerId));
        assertThat(logs).hasSize(1);
        assertThat(logs.get(0).getBizType()).isEqualTo(MallPointsLog.BIZ_GRANT);
        assertThat(logs.get(0).getBalancePoints()).as("流水要带变动后余额,便于对账").isEqualTo(90);
    }

    @Test
    @DisplayName("发放幂等:同一订单发两次只发一次")
    void grantIsIdempotentPerOrder() {
        long orderId = newOrderId();

        int first = inTenant(() -> memberPointsService.grant(customerId, orderId, new BigDecimal("100.00")));
        int second = inTenant(() -> memberPointsService.grant(customerId, orderId, new BigDecimal("100.00")));

        assertThat(first).isEqualTo(100);
        assertThat(second).as("第二次应当被幂等挡掉").isZero();
        assertThat(pointsOf(customerId)).isEqualTo(100);
        assertThat(usableBatchSum(customerId)).isEqualTo(100);
    }

    @Test
    @DisplayName("实付不足 1 元不发:0 元订单不该产生一条 0 的流水")
    void grantSkipsTinyAmounts() {
        assertThat(inTenant(() -> memberPointsService.grant(customerId, newOrderId(), new BigDecimal("0.99"))))
                .as("不足 1 元向下取整就是 0")
                .isZero();
        assertThat(inTenant(() -> memberPointsService.grant(customerId, newOrderId(), BigDecimal.ZERO))).isZero();

        assertThat(pointsOf(customerId)).isZero();
        assertThat(inTenant(() -> pointsLogRepository.findByCustomerIdOrderByIdDesc(customerId)))
                .as("没发就不该留流水,否则对账时多出一批 0 的记录")
                .isEmpty();
    }

    // ---------------------------------------------------------------- 抵现

    @Test
    @DisplayName("抵现按 FIFO:先过期的批次先扣")
    void redeemConsumesEarliestExpiringBatchFirst() {
        Long earliest = grantViaBatchWithExpiry("30.00", LocalDateTime.now().plusMonths(1));
        Long latest = grantViaBatchWithExpiry("50.00", LocalDateTime.now().plusMonths(10));

        inTenant(() -> memberPointsService.redeem(customerId, newOrderId(), 40));

        assertThat(remainOf(earliest)).as("1 个月后过期的批次应当被先用光").isZero();
        assertThat(remainOf(latest)).as("不够的部分才轮到 10 个月后过期的批次").isEqualTo(40);
        assertThat(pointsOf(customerId)).isEqualTo(40);
    }

    @Test
    @DisplayName("抵现余额不足:拒绝且一分不扣")
    void redeemRejectsInsufficientBalance() {
        inTenant(() -> memberPointsService.grant(customerId, newOrderId(), new BigDecimal("10.00")));
        long orderId = newOrderId();

        assertThatThrownBy(() -> inTenant(() -> memberPointsService.redeem(customerId, orderId, 11)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("积分余额不足");

        assertThat(pointsOf(customerId)).isEqualTo(10);
        assertThat(usableBatchSum(customerId)).as("被拒绝时批次也必须原封不动").isEqualTo(10);
    }

    // ---------------------------------------------------------------- 订单关闭退回

    @Test
    @DisplayName("订单关闭退回原批次:剩余与过期时间都回到原样")
    void returnRestoresOriginalBatchAndExpiry() {
        inTenant(() -> memberPointsService.grant(customerId, newOrderId(), new BigDecimal("100.00")));
        Long batchId = batchIdsOf(customerId).get(0);
        LocalDateTime expireBefore = expireTimeOf(batchId);
        long orderId = newOrderId();
        inTenant(() -> memberPointsService.redeem(customerId, orderId, 60));
        assertThat(pointsOf(customerId)).isEqualTo(40);

        int returned = inTenant(() -> memberPointsService.returnForOrder(orderId, customerId));

        assertThat(returned).isEqualTo(60);
        assertThat(pointsOf(customerId)).isEqualTo(100);
        assertThat(remainOf(batchId)).as("退回原批次,不是新建一个").isEqualTo(100);
        assertThat(expireTimeOf(batchId))
                .as("过期时间必须沿用原来的 —— 新建批次等于让积分无限续期")
                .isEqualTo(expireBefore);
        assertThat(usableBatchSum(customerId)).isEqualTo(100);
    }

    @Test
    @DisplayName("退回幂等:重复取消不会退两次")
    void returnIsIdempotent() {
        inTenant(() -> memberPointsService.grant(customerId, newOrderId(), new BigDecimal("100.00")));
        long orderId = newOrderId();
        inTenant(() -> memberPointsService.redeem(customerId, orderId, 30));

        assertThat(inTenant(() -> memberPointsService.returnForOrder(orderId, customerId))).isEqualTo(30);
        assertThat(inTenant(() -> memberPointsService.returnForOrder(orderId, customerId)))
                .as("第二次已经没有占用记录了").isZero();
        assertThat(pointsOf(customerId)).isEqualTo(100);
    }

    @Test
    @DisplayName("退回不复活已过期的批次:那部分该随过期清零")
    void returnDoesNotReviveExpiredBatch() {
        inTenant(() -> memberPointsService.grant(customerId, newOrderId(), new BigDecimal("100.00")));
        long orderId = newOrderId();
        inTenant(() -> memberPointsService.redeem(customerId, orderId, 70));
        // 占用期间批次过期了
        backdateBatchExpiry(LocalDateTime.now().minusDays(1));

        int returned = inTenant(() -> memberPointsService.returnForOrder(orderId, customerId));

        assertThat(returned).as("已过期的不退回").isZero();
        assertThat(usableBatchSum(customerId)).as("过期批次不该被退回重新变得可用").isZero();
        assertThat(inTenant(() -> memberPointsService.usablePoints(customerId))).isZero();
    }

    // ---------------------------------------------------------------- 过期

    @Test
    @DisplayName("可用积分懒过期:已到期的批次在读取那一刻就被清掉")
    void usablePointsLazilyExpiresOverdueBatches() {
        inTenant(() -> memberPointsService.grant(customerId, newOrderId(), new BigDecimal("100.00")));
        backdateBatchExpiry(LocalDateTime.now().minusSeconds(1));

        int usable = inTenant(() -> memberPointsService.usablePoints(customerId));

        assertThat(usable).as("过期的积分不能再用于抵现").isZero();
        assertThat(pointsOf(customerId)).isZero();
        assertThat(usableBatchSum(customerId)).isZero();
        assertThat(inTenant(() -> pointsLogRepository.findByCustomerIdOrderByIdDesc(customerId)))
                .as("过期要有流水,否则用户看不到积分去哪了")
                .anySatisfy(log -> assertThat(log.getBizType()).isEqualTo(MallPointsLog.BIZ_EXPIRE));
    }

    @Test
    @DisplayName("过期任务:清掉已到期批次并写流水,未到期的不动")
    void expireBatchesClearsOnlyOverdue() {
        inTenant(() -> memberPointsService.grant(customerId, newOrderId(), new BigDecimal("80.00")));
        inTenant(() -> memberPointsService.grant(otherCustomerId, newOrderId(), new BigDecimal("50.00")));
        // 只让主客户的批次过期
        jdbcTemplate.update("update mall_points_batch set expire_time = ? where tenant_id = ? and customer_id = ?",
                Timestamp.valueOf(LocalDateTime.now().minusSeconds(1)), TENANT_ID, customerId);

        int handled = inTenant(() -> memberPointsService.expireBatches(500));

        assertThat(handled).isEqualTo(1);
        assertThat(pointsOf(customerId)).isZero();
        assertThat(pointsOf(otherCustomerId)).as("没到期的一分不动").isEqualTo(50);
    }

    @Test
    @DisplayName("过期任务幂等:重复跑不会把同一批重复清零")
    void expireBatchesIsIdempotent() {
        inTenant(() -> memberPointsService.grant(customerId, newOrderId(), new BigDecimal("80.00")));
        backdateBatchExpiry(LocalDateTime.now().minusSeconds(1));

        assertThat(inTenant(() -> memberPointsService.expireBatches(500))).isEqualTo(1);
        assertThat(inTenant(() -> memberPointsService.expireBatches(500)))
                .as("批次剩余已是 0,扫描条件不再命中").isZero();
        assertThat(pointsOf(customerId)).isZero();
    }

    // ---------------------------------------------------------------- 成长值、等级

    @Test
    @DisplayName("等级按滚动成长值晋升,够不着门槛时回到默认等级")
    void levelFollowsRollingGrowth() {
        Long silver = createLevel("用例银卡" + System.nanoTime(), 1, 100);
        Long gold = createLevel("用例金卡" + System.nanoTime(), 2, 300);

        inTenant(() -> memberPointsService.grant(customerId, newOrderId(), new BigDecimal("150.00")));

        assertThat(levelOf(customerId)).as("150 成长值够银卡(100)不够金卡(300)").isEqualTo(silver);

        inTenant(() -> memberPointsService.grant(customerId, newOrderId(), new BigDecimal("200.00")));

        assertThat(levelOf(customerId)).as("累计 350 应当升到金卡").isEqualTo(gold);
    }

    @Test
    @DisplayName("滚动窗口滚出老值 → 降级(这是降级的唯一来源)")
    void rollingWindowDropsOldGrowthAndDowngrades() {
        Long silver = createLevel("用例银卡" + System.nanoTime(), 1, 100);
        inTenant(() -> memberPointsService.grant(customerId, newOrderId(), new BigDecimal("200.00")));
        assertThat(levelOf(customerId)).isEqualTo(silver);

        // 把这笔成长值推到 12 个月窗口之外
        jdbcTemplate.update("update mall_growth_log set create_time = ? where tenant_id = ? and customer_id = ?",
                Timestamp.valueOf(LocalDateTime.now().minusMonths(13)), TENANT_ID, customerId);

        int recomputed = inTenant(() -> memberPointsService.refreshRollingGrowth());

        assertThat(recomputed).as("至少重算了这个客户").isPositive();
        assertThat(growthOf(customerId)).as("窗口外的成长值不再计入").isZero();
        assertThat(levelOf(customerId)).as("够不着门槛就该降回默认等级").isNull();
    }

    // ---------------------------------------------------------------- 管理端调整

    @Test
    @DisplayName("管理端手动调整:加积分要建批次,减积分按 FIFO 且扣到 0 为止")
    void manualAdjustKeepsBatchInSync() {
        inTenantRun(() -> memberPointsService.manualAdjust(customerId, 60, 60, "客诉补偿"));

        assertThat(pointsOf(customerId)).isEqualTo(60);
        assertThat(usableBatchSum(customerId))
                .as("只加汇总余额不建批次的话,可用积分立刻就对不上")
                .isEqualTo(60);
        assertThat(growthOf(customerId)).isEqualTo(60);

        // 扣得比余额多:只扣到 0,不产生负积分
        inTenantRun(() -> memberPointsService.manualAdjust(customerId, -100, -100, "追回"));

        assertThat(pointsOf(customerId)).isZero();
        assertThat(growthOf(customerId)).isZero();
        assertThat(usableBatchSum(customerId)).isZero();
    }

    @Test
    @DisplayName("不变量:任何操作序列之后,余额都等于未过期批次的剩余之和")
    void balanceAlwaysEqualsSumOfUsableBatches() {
        inTenant(() -> memberPointsService.grant(customerId, newOrderId(), new BigDecimal("100.00")));
        inTenant(() -> memberPointsService.grant(customerId, newOrderId(), new BigDecimal("50.00")));

        long orderId = newOrderId();
        inTenant(() -> memberPointsService.redeem(customerId, orderId, 120));
        inTenant(() -> memberPointsService.returnForOrder(orderId, customerId));
        inTenantRun(() -> memberPointsService.manualAdjust(customerId, -30, 0, "扣减"));
        backdateBatchExpiry(LocalDateTime.now().minusSeconds(1));
        inTenant(() -> memberPointsService.usablePoints(customerId));

        assertThat(pointsOf(customerId)).isEqualTo(usableBatchSum(customerId));
    }

    // ---------------------------------------------------------------- 辅助

    private void inTenantRun(Runnable action) {
        inTenant(() -> {
            action.run();
            return null;
        });
    }

    private long newOrderId() {
        long id = SnowflakeIdGenerator.nextId();
        createdOrderIds.add(id);
        return id;
    }

    /** 发放一笔并把它批次的过期时间改成指定值(FIFO 用例需要不同过期时间)。 */
    private Long grantViaBatchWithExpiry(String amount, LocalDateTime expiry) {
        inTenant(() -> memberPointsService.grant(customerId, newOrderId(), new BigDecimal(amount)));
        List<Long> ids = batchIdsOf(customerId);
        Long latest = ids.get(ids.size() - 1);
        inTenant(() -> {
            jdbcTemplate.update("update mall_points_batch set expire_time = ? where id = ?",
                    Timestamp.valueOf(expiry), latest);
            return null;
        });
        return latest;
    }

    private Long createLevel(String name, int sort, int growthThreshold) {
        return inTenant(() -> {
            MallMemberLevel level = new MallMemberLevel();
            level.setLevelName(name);
            level.setLevelSort(sort);
            level.setGrowthThreshold(growthThreshold);
            level.setStatus(1);
            Long id = levelRepository.save(level).getId();
            createdLevelIds.add(id);
            return id;
        });
    }

    private void backdateBatchExpiry(LocalDateTime expiry) {
        jdbcTemplate.update("update mall_points_batch set expire_time = ? where tenant_id = ? and customer_id = ?",
                Timestamp.valueOf(expiry), TENANT_ID, customerId);
    }

    private int pointsOf(Long id) {
        return inTenant(() -> customerRepository.findById(id).orElseThrow().getPoints());
    }

    private int growthOf(Long id) {
        return inTenant(() -> customerRepository.findById(id).orElseThrow().getGrowthValue());
    }

    private Long levelOf(Long id) {
        MallCustomer customer = inTenant(() -> customerRepository.findById(id).orElseThrow());
        return customer.getMemberLevelId();
    }

    private List<Long> batchIdsOf(Long id) {
        return inTenant(() -> batchRepository.findUsable(id, TENANT_ID, LocalDateTime.now()).stream()
                .map(MallPointsBatch::getId)
                .toList());
    }

    private int usableBatchSum(Long id) {
        Long sum = inTenant(() -> batchRepository.sumUsableRemain(id, TENANT_ID, LocalDateTime.now()));
        return sum == null ? 0 : sum.intValue();
    }

    private int remainOf(Long batchId) {
        return inTenant(() -> batchRepository.findById(batchId).orElseThrow().getRemainPoints());
    }

    private LocalDateTime expireTimeOf(Long batchId) {
        return inTenant(() -> batchRepository.findById(batchId).orElseThrow().getExpireTime());
    }
}

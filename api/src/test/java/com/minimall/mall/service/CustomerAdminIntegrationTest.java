package com.minimall.mall.service;

import com.minimall.common.BusinessException;
import com.minimall.common.PageResult;
import com.minimall.infra.id.SnowflakeIdGenerator;
import com.minimall.mall.api.dto.CustomerDetailView;
import com.minimall.mall.api.dto.CustomerView;
import com.minimall.mall.api.dto.MemberValueAdjustRequest;
import com.minimall.mall.domain.repository.MallPointsBatchRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 管理端客户管理(商城设计文档 3.11)。
 *
 * <p>权限码那部分由 {@code AdminPermissionHttpTest} 覆盖(它会遍历所有管理端端点),
 * 这里只验"能不能查到、调整有没有真的落到积分账本上"。
 */
class CustomerAdminIntegrationTest extends MallClientServiceTestBase {

    @Autowired
    private CustomerAdminService customerAdminService;
    @Autowired
    private MemberPointsService memberPointsService;
    @Autowired
    private MallPointsBatchRepository batchRepository;

    @AfterEach
    void tearDownMemberData() {
        inTenant(() -> {
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
            return null;
        });
    }

    @Test
    @DisplayName("客户列表:昵称与手机号都能筛")
    void pageFiltersByNicknameAndPhone() {
        String keyword = "查" + suffix();
        inTenant(() -> {
            var customer = customerRepository.findById(customerId).orElseThrow();
            customer.setNickname(keyword);
            customer.setPhone("13900000001");
            customerRepository.save(customer);
            return null;
        });

        PageResult<CustomerView> byNickname = inTenant(() -> customerAdminService.page(keyword, null, 1, 10));
        assertThat(byNickname.list()).hasSize(1);
        assertThat(byNickname.list().get(0).id()).isEqualTo(customerId);
        assertThat(byNickname.list().get(0).memberLevelName())
                .as("列表给的是等级展示名,不是雪花 ID")
                .isNotBlank();

        assertThat(inTenant(() -> customerAdminService.page(null, "13900000001", 1, 10)).list())
                .as("手机号同样能筛出来")
                .hasSize(1);
        assertThat(inTenant(() -> customerAdminService.page("查不到的名字", null, 1, 10)).total()).isZero();
    }

    @Test
    @DisplayName("客户详情:积分与成长值两种流水都给(两者分账,只看一种查不出问题)")
    void detailReturnsBothLedgers() {
        inTenant(() -> memberPointsService.grant(customerId, SnowflakeIdGenerator.nextId(),
                new BigDecimal("100.00")));

        CustomerDetailView detail = inTenant(() -> customerAdminService.detail(customerId));

        assertThat(detail.customer().points()).isEqualTo(100);
        assertThat(detail.customer().growthValue()).isEqualTo(100);
        assertThat(detail.pointsLogs()).as("积分流水").hasSize(1);
        assertThat(detail.pointsLogs().get(0).bizTypeText()).isEqualTo("确认收货获得");
        assertThat(detail.growthLogs()).as("成长值流水").hasSize(1);
        assertThat(detail.growthLogs().get(0).bizTypeText()).isEqualTo("确认收货获得");
    }

    @Test
    @DisplayName("手动调整:余额与批次成对维护,等级跟着重算")
    void adjustKeepsBalanceAndBatchInSync() {
        inTenantRun(() -> customerAdminService.adjust(customerId,
                new MemberValueAdjustRequest(120, 120, "客诉补偿")));

        assertThat(pointsOf(customerId)).isEqualTo(120);
        assertThat(batchSum(customerId))
                .as("只加汇总余额不建批次的话,可用积分立刻就对不上")
                .isEqualTo(120);
        assertThat(growthOf(customerId)).isEqualTo(120);
        assertThat(inTenant(() -> customerAdminService.detail(customerId)).pointsLogs().get(0).remark())
                .as("手工改动必须留下原因,否则事后对账无从解释")
                .isEqualTo("客诉补偿");
    }

    @Test
    @DisplayName("手动扣减扣到 0 为止:不允许把余额扣成负数")
    void adjustClampsDeductionToZero() {
        inTenantRun(() -> customerAdminService.adjust(customerId,
                new MemberValueAdjustRequest(50, 50, "补发")));

        // 想扣 200,但只有 50
        inTenantRun(() -> customerAdminService.adjust(customerId,
                new MemberValueAdjustRequest(-200, -200, "追回")));

        assertThat(pointsOf(customerId)).isZero();
        assertThat(growthOf(customerId)).isZero();
        assertThat(batchSum(customerId)).isZero();
    }

    @Test
    @DisplayName("两个调整量都为 0 时拒绝:那是一次什么都没做的操作")
    void adjustRejectsNoop() {
        assertThatThrownBy(() -> inTenantRun(() -> customerAdminService.adjust(customerId,
                new MemberValueAdjustRequest(0, 0, "什么都没改"))))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("至少要调整一项");
    }

    @Test
    @DisplayName("调整不存在的客户:按不存在处理,不泄露其它租户的客户")
    void adjustRejectsUnknownCustomer() {
        assertThatThrownBy(() -> inTenantRun(() -> customerAdminService.adjust(
                SnowflakeIdGenerator.nextId(), new MemberValueAdjustRequest(10, 0, "乱试"))))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("客户不存在");
    }

    // ---------------------------------------------------------------- 辅助

    /** inTenant 收 Supplier,void 方法用这个包一层。 */
    private void inTenantRun(Runnable action) {
        inTenant(() -> {
            action.run();
            return null;
        });
    }

    private String suffix() {
        return String.valueOf(System.nanoTime() % 100000);
    }

    private int pointsOf(Long id) {
        return inTenant(() -> customerRepository.findById(id).orElseThrow().getPoints());
    }

    private int growthOf(Long id) {
        return inTenant(() -> customerRepository.findById(id).orElseThrow().getGrowthValue());
    }

    private int batchSum(Long id) {
        Long sum = inTenant(() -> batchRepository.sumUsableRemain(id, TENANT_ID, LocalDateTime.now()));
        return sum == null ? 0 : sum.intValue();
    }
}

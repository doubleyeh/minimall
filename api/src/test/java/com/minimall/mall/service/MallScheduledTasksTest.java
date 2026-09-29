package com.minimall.mall.service;

import com.minimall.infra.schedule.ScheduledTaskLock;
import com.minimall.mall.infra.pay.WxPayClient;
import com.minimall.sys.service.support.DictIntReader;
import com.minimall.sys.service.support.TenantTaskRunner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 逐租户任务的部分失败必须能被看见(架构文档 6.2)。
 *
 * <p>{@link TenantTaskRunner} 把单租户异常吞在循环里,任务侧若也只记一条 warn,
 * 执行历史(见 {@link ScheduledTaskLock#runIfNotLocked})就会把"有租户没处理"记成成功 —— 这正是要堵的洞。
 */
class MallScheduledTasksTest {

    private TenantTaskRunner tenantTaskRunner;
    private MallScheduledTasks tasks;

    @BeforeEach
    void setUp() {
        tenantTaskRunner = mock(TenantTaskRunner.class);
        ScheduledTaskLock taskLock = mock(ScheduledTaskLock.class);
        tasks = new MallScheduledTasks(
                tenantTaskRunner,
                mock(OrderService.class),
                mock(AfterSaleService.class),
                mock(MarketingMaintenanceService.class),
                mock(DictIntReader.class),
                mock(MemberPointsService.class),
                mock(PayService.class),
                mock(WxPayClient.class),
                taskLock);
        // 让锁永远抢得到、并真的执行任务体
        when(taskLock.runIfNotLocked(anyString(), any(Runnable.class))).thenAnswer(invocation -> {
            ((Runnable) invocation.getArgument(1)).run();
            return true;
        });
    }

    @Test
    @DisplayName("有租户失败:任务抛异常,执行历史据此记成失败")
    void throwsWhenSomeTenantsFail() {
        when(tenantTaskRunner.runForEachTenant(anyString(), anyLong(), any()))
                .thenReturn(new TenantTaskRunner.RunResult("关闭超时未支付订单", 1, List.of(7L, 9L)));

        assertThatThrownBy(() -> tasks.closeTimeoutOrders())
                .as("只打 warn 的话,执行历史会把「部分租户没处理」显示成成功")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("7")
                .hasMessageContaining("9");
    }

    @Test
    @DisplayName("全部租户成功:不抛异常")
    void doesNotThrowWhenAllTenantsSucceed() {
        when(tenantTaskRunner.runForEachTenant(anyString(), anyLong(), any()))
                .thenReturn(new TenantTaskRunner.RunResult("关闭超时未支付订单", 2, List.of()));

        assertThatCode(() -> tasks.closeTimeoutOrders()).doesNotThrowAnyException();
    }
}

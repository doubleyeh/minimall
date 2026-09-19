package com.minimall.mall.service;

import com.minimall.mall.api.dto.AfterSaleApplyRequest;
import com.minimall.mall.api.dto.AfterSaleView;
import com.minimall.common.PageResult;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 售后(商城设计文档 3.9)。
 *
 * <p>状态机有 10 个态、4 个终态,并且**每条流转都要联动订单状态**(进行中把订单置为"售后中",
 * 终态后按结果回退)。所有流转都收敛在这个服务里,原因和订单一样:状态机散落在多处,
 * 一定会出现"某个入口忘了写日志"或"漏了库存回补"。
 *
 * <p>本期没有独立客服角色:客服介入(status=7)由商家或超管在管理端处理,
 * 但操作人类型仍记 {@code 4-平台客服},为将来接入独立角色预留(文档 3.9)。
 */
public interface AfterSaleService {

    // ---------------------------------------------------------- 小程序端

    /** 申请售后。 */
    Long apply(AfterSaleApplyRequest request);

    /** 买家撤销(status=5 → 10,或 status=6 → 10)。 */
    void cancelByBuyer(Long afterSaleId);

    /** 买家提交退货物流(status=2 → 3)。 */
    void submitReturnLogistics(Long afterSaleId, String company, String no);

    List<AfterSaleView> mine(Integer status);

    AfterSaleView detailForBuyer(Long afterSaleId);

    // ---------------------------------------------------------- 管理端

    PageResult<AfterSaleView> page(Integer status, String afterSaleNo, int pageNo, int pageSize);

    AfterSaleView detail(Long afterSaleId);

    /**
     * 商家同意。仅退款({@code type=1})直接进入终态并退款;
     * 退货退款/换货({@code type=2/3})进入"待买家退货"。
     *
     * @param refundAmount 可下调退款金额(不超过申请金额),传空表示按申请金额
     */
    void approve(Long afterSaleId, BigDecimal refundAmount);

    /** 商家拒绝申请(status=1 → 5)。 */
    void reject(Long afterSaleId, String reason);

    /** 商家确认收到退货(status=3 → 4):触发退款或换货重发。 */
    void confirmReturnReceived(Long afterSaleId, Long newSkuId, String logisticsCompany, String logisticsNo);

    /** 商家拒绝收货(status=3 → 6)。 */
    void rejectReturn(Long afterSaleId, String reason);

    /** 客服介入申请(status=5/6 → 7)。 */
    void requestArbitration(Long afterSaleId);

    /** 客服仲裁(status=7 → 8 通过 / 9 驳回)。 */
    void arbitrate(Long afterSaleId, boolean pass, String remark);

    // ---------------------------------------------------------- 定时任务

    /** 商家超时未处理(status=1):仅退款自动同意,退货退款/换货自动同意进入待退货。 */
    int autoApproveTimeout(LocalDateTime updatedBefore);

    /** 买家超时未退货(status=2)→ 10。 */
    int autoCloseTimeout(LocalDateTime updatedBefore);

    /** 商家超时未确认收货(status=3)→ 4。 */
    int autoReceiveTimeout(LocalDateTime updatedBefore);
}

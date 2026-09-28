package com.minimall.mall.service;

import com.minimall.common.PageResult;
import com.minimall.mall.api.dto.CustomerDetailView;
import com.minimall.mall.api.dto.CustomerView;
import com.minimall.mall.api.dto.MemberValueAdjustRequest;

/**
 * 管理端客户管理(商城设计文档 3.11)。
 *
 * <p>在此之前积分只在客户端可见:运营看不到某个客户的积分与成长值,客诉时也无法人工补分 ——
 * 而"手工补一笔"是积分体系上线后最常见的运营需求之一。
 */
public interface CustomerAdminService {

    /**
     * 客户分页查询。
     *
     * @param nickname 昵称模糊匹配,可空
     * @param phone    手机号模糊匹配,可空
     */
    PageResult<CustomerView> page(String nickname, String phone, int pageNo, int pageSize);

    /** 客户详情:摘要 + 最近的积分与成长值流水。 */
    CustomerDetailView detail(Long customerId);

    /** 手动调整积分与成长值。走 {@link MemberPointsService#manualAdjust},正负数都支持。 */
    void adjust(Long customerId, MemberValueAdjustRequest request);
}

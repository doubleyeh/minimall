package com.minimall.mall.service;

import com.minimall.api.mall.dto.ClientProfileUpdateRequest;
import com.minimall.api.mall.dto.ClientProfileView;

/**
 * 小程序端个人中心(商城设计文档 3.1)。
 *
 * <p>所有操作都作用于**当前令牌对应的客户**:接口不接受 customerId 参数。
 */
public interface ClientProfileService {

    /** 个人资料 + 订单角标。 */
    ClientProfileView profile();

    /** 更新昵称/头像/性别(积分与身份字段不可改)。 */
    void update(ClientProfileUpdateRequest request);
}

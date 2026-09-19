package com.minimall.mall.service.impl;

import com.minimall.mall.api.dto.ClientProfileUpdateRequest;
import com.minimall.mall.api.dto.ClientProfileView;
import com.minimall.common.BusinessException;
import com.minimall.common.ErrorCode;
import com.minimall.mall.domain.MallCustomer;
import com.minimall.mall.domain.MallOrder;
import com.minimall.mall.domain.repository.MallCustomerRepository;
import com.minimall.mall.domain.repository.MallOrderRepository;
import com.minimall.mall.infra.auth.ClientContext;
import com.minimall.mall.service.ClientProfileService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 小程序端个人中心实现(商城设计文档 3.1)。
 */
@Service
@Transactional
public class ClientProfileServiceImpl implements ClientProfileService {

    private static final int GENDER_UNKNOWN = 0;
    private static final int GENDER_MALE = 1;
    private static final int GENDER_FEMALE = 2;

    private final MallCustomerRepository customerRepository;
    private final MallOrderRepository orderRepository;

    public ClientProfileServiceImpl(MallCustomerRepository customerRepository,
                                    MallOrderRepository orderRepository) {
        this.customerRepository = customerRepository;
        this.orderRepository = orderRepository;
    }

    @Override
    public ClientProfileView profile() {
        Long customerId = ClientContext.requireCustomerId();
        MallCustomer customer = load(customerId);
        ClientProfileView.OrderCounts counts = new ClientProfileView.OrderCounts(
                count(customerId, MallOrder.STATUS_PENDING_PAY),
                count(customerId, MallOrder.STATUS_PENDING_SHIP),
                count(customerId, MallOrder.STATUS_PENDING_RECEIVE),
                count(customerId, MallOrder.STATUS_FINISHED));
        return new ClientProfileView(customer.getId(), customer.getNickname(), customer.getAvatarUrl(),
                customer.getPhone(), customer.getGender(), customer.getPoints(), customer.getGrowthValue(),
                counts);
    }

    @Override
    public void update(ClientProfileUpdateRequest request) {
        Long customerId = ClientContext.requireCustomerId();
        MallCustomer customer = load(customerId);
        if (request.nickname() != null && !request.nickname().isBlank()) {
            customer.setNickname(request.nickname().trim());
        }
        if (request.avatarUrl() != null && !request.avatarUrl().isBlank()) {
            customer.setAvatarUrl(request.avatarUrl().trim());
        }
        if (request.gender() != null) {
            int gender = request.gender();
            if (gender != GENDER_UNKNOWN && gender != GENDER_MALE && gender != GENDER_FEMALE) {
                throw new BusinessException(ErrorCode.PARAM_INVALID, "性别取值不合法");
            }
            customer.setGender(gender);
        }
    }

    private long count(Long customerId, int status) {
        return orderRepository.countByCustomerIdAndStatus(customerId, status);
    }

    private MallCustomer load(Long customerId) {
        return customerRepository.findById(customerId)
                // 能走到这里说明令牌有效但客户已不在(被清理或数据异常),按未登录处理
                .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHORIZED));
    }
}

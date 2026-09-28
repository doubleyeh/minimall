package com.minimall.mall.service.impl;

import com.minimall.mall.api.dto.ClientProfileUpdateRequest;
import com.minimall.mall.api.dto.ClientProfileView;
import com.minimall.common.BusinessException;
import com.minimall.common.ErrorCode;
import com.minimall.mall.domain.MallCustomer;
import com.minimall.mall.domain.MallMemberLevel;
import com.minimall.mall.domain.MallOrder;
import com.minimall.mall.domain.repository.MallCustomerRepository;
import com.minimall.mall.domain.repository.MallMemberLevelRepository;
import com.minimall.mall.domain.repository.MallOrderRepository;
import com.minimall.mall.infra.auth.ClientContext;
import com.minimall.mall.service.ClientProfileService;
import com.minimall.infra.tenant.TenantContext;
import org.springframework.stereotype.Service;

import java.util.List;
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
    private final MallMemberLevelRepository levelRepository;

    public ClientProfileServiceImpl(MallCustomerRepository customerRepository,
                                    MallOrderRepository orderRepository,
                                    MallMemberLevelRepository levelRepository) {
        this.customerRepository = customerRepository;
        this.orderRepository = orderRepository;
        this.levelRepository = levelRepository;
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
        LevelInfo level = levelInfo(customer);
        return new ClientProfileView(customer.getId(), customer.getNickname(), customer.getAvatarUrl(),
                customer.getPhone(), customer.getGender(), customer.getPoints(), customer.getGrowthValue(),
                level.name(), level.growthToNext(), counts);
    }

    /**
     * 当前等级名与"还差多少成长值升级"。
     *
     * <p>等级定义是每租户自建的,所以查出来的列表可能是空的 —— 那时所有客户都是"普通会员"。
     */
    private LevelInfo levelInfo(MallCustomer customer) {
        List<MallMemberLevel> levels = levelRepository.findByStatusAndTenantIdOrderByGrowthThresholdDesc(
                1, TenantContext.getTenantId());
        String name = levels.stream()
                .filter(level -> level.getId().equals(customer.getMemberLevelId()))
                .map(MallMemberLevel::getLevelName)
                .findFirst()
                .orElse(MallMemberLevel.DEFAULT_LEVEL_NAME);
        int growth = customer.getGrowthValue() == null ? 0 : customer.getGrowthValue();
        // 门槛里第一个还没够着的就是下一级;都够着了说明已是最高等级
        Integer toNext = levels.stream()
                .map(MallMemberLevel::getGrowthThreshold)
                .filter(threshold -> threshold != null && threshold > growth)
                .min(Integer::compareTo)
                .map(threshold -> threshold - growth)
                .orElse(null);
        return new LevelInfo(name, toNext);
    }

    private record LevelInfo(String name, Integer growthToNext) {
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

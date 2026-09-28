package com.minimall.mall.service.impl;

import com.minimall.common.BusinessException;
import com.minimall.common.ErrorCode;
import com.minimall.common.PageResult;
import com.minimall.mall.api.dto.CustomerDetailView;
import com.minimall.mall.api.dto.CustomerView;
import com.minimall.mall.api.dto.GrowthLogView;
import com.minimall.mall.api.dto.MemberValueAdjustRequest;
import com.minimall.mall.api.dto.PointsLogView;
import com.minimall.mall.domain.MallCustomer;
import com.minimall.mall.domain.MallGrowthLog;
import com.minimall.mall.domain.QMallCustomer;
import com.minimall.mall.domain.QMallGrowthLog;
import com.minimall.mall.domain.repository.MallCustomerRepository;
import com.minimall.mall.domain.repository.MallGrowthLogRepository;
import com.minimall.mall.service.CustomerAdminService;
import com.minimall.mall.service.MemberLevelService;
import com.minimall.mall.service.MemberPointsService;
import com.querydsl.core.BooleanBuilder;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 管理端客户管理实现(商城设计文档 3.11)。
 *
 * <p>这里**只做查询与转发**:积分与成长值的实际改动全部交给
 * {@link MemberPointsService#manualAdjust} —— 余额与批次的一致性、等级重算那些不变量
 * 只在那一处维护,不在这里再抄一份。
 */
@Service
@Transactional
public class CustomerAdminServiceImpl implements CustomerAdminService {

    /** 详情里各取最近多少条流水。给全部没有意义,而"翻到第 20 条才看到问题"也说明该查数据库了。 */
    private static final int DETAIL_LOG_LIMIT = 20;

    private final MallCustomerRepository customerRepository;
    private final MallGrowthLogRepository growthLogRepository;
    private final MemberPointsService memberPointsService;
    private final MemberLevelService memberLevelService;

    public CustomerAdminServiceImpl(MallCustomerRepository customerRepository,
                                    MallGrowthLogRepository growthLogRepository,
                                    MemberPointsService memberPointsService,
                                    MemberLevelService memberLevelService) {
        this.customerRepository = customerRepository;
        this.growthLogRepository = growthLogRepository;
        this.memberPointsService = memberPointsService;
        this.memberLevelService = memberLevelService;
    }

    @Override
    public PageResult<CustomerView> page(String nickname, String phone, int pageNo, int pageSize) {
        QMallCustomer qCustomer = QMallCustomer.mallCustomer;
        BooleanBuilder where = new BooleanBuilder();
        if (nickname != null && !nickname.isBlank()) {
            where.and(qCustomer.nickname.contains(nickname.trim()));
        }
        if (phone != null && !phone.isBlank()) {
            where.and(qCustomer.phone.contains(phone.trim()));
        }
        Page<MallCustomer> page = customerRepository.findAll(where, PageRequest.of(
                Math.max(pageNo - 1, 0),
                Math.max(pageSize, 1),
                Sort.by(Sort.Direction.DESC, "id")));
        List<CustomerView> views = page.getContent().stream().map(this::toView).toList();
        return PageResult.of(page.getTotalElements(), views);
    }

    @Override
    public CustomerDetailView detail(Long customerId) {
        MallCustomer customer = load(customerId);

        QMallGrowthLog qGrowth = QMallGrowthLog.mallGrowthLog;
        List<GrowthLogView> growthLogs = growthLogRepository.findAll(
                        new BooleanBuilder(qGrowth.customerId.eq(customerId)),
                        PageRequest.of(0, DETAIL_LOG_LIMIT, Sort.by(Sort.Direction.DESC, "id")))
                .getContent().stream()
                .map(CustomerAdminServiceImpl::toGrowthView)
                .toList();

        return new CustomerDetailView(toView(customer),
                memberPointsService.pageLogs(customerId, 1, DETAIL_LOG_LIMIT).list(),
                growthLogs);
    }

    @Override
    public void adjust(Long customerId, MemberValueAdjustRequest request) {
        // 先确认客户存在且属于当前租户:不然 manualAdjust 会去改一个不属于这里的客户
        load(customerId);
        memberPointsService.manualAdjust(customerId, request.pointsDelta(), request.growthDelta(),
                request.remark());
    }

    private MallCustomer load(Long customerId) {
        return customerRepository.findById(customerId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "客户不存在"));
    }

    private CustomerView toView(MallCustomer customer) {
        return new CustomerView(customer.getId(), customer.getNickname(), customer.getPhone(),
                memberLevelService.displayName(customer.getMemberLevelId()), customer.getPoints(),
                customer.getGrowthValue(), customer.getRegisterTime());
    }

    private static GrowthLogView toGrowthView(MallGrowthLog log) {
        return new GrowthLogView(log.getId(), log.getChangeGrowth(), log.getBizType(),
                GrowthLogView.text(log.getBizType()), log.getRemark(), log.getCreateTime());
    }
}

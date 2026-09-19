package com.minimall.mall.service.impl;

import com.minimall.api.mall.dto.MemberLevelSaveRequest;
import com.minimall.api.mall.dto.MemberLevelView;
import com.minimall.common.BusinessException;
import com.minimall.common.ErrorCode;
import com.minimall.mall.domain.MallMemberLevel;
import com.minimall.mall.domain.repository.MallCustomerRepository;
import com.minimall.mall.domain.repository.MallMemberLevelRepository;
import com.minimall.mall.service.MemberLevelService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

/**
 * 会员等级实现(商城设计文档 5.2)。
 */
@Service
@Transactional
public class MemberLevelServiceImpl implements MemberLevelService {

    private static final int STATUS_ENABLED = 1;

    private final MallMemberLevelRepository levelRepository;
    private final MallCustomerRepository customerRepository;

    public MemberLevelServiceImpl(MallMemberLevelRepository levelRepository,
                                  MallCustomerRepository customerRepository) {
        this.levelRepository = levelRepository;
        this.customerRepository = customerRepository;
    }

    @Override
    public List<MemberLevelView> list() {
        return levelRepository.findByOrderByLevelSortAsc().stream()
                .map(level -> new MemberLevelView(level.getId(), level.getLevelName(), level.getLevelSort(),
                        level.getGrowthThreshold(), level.getDiscountRate(), level.getStatus()))
                .toList();
    }

    @Override
    public Long create(MemberLevelSaveRequest request) {
        if (levelRepository.existsByLevelName(request.levelName())) {
            throw new BusinessException(ErrorCode.DATA_CONFLICT, "等级名称已存在");
        }
        MallMemberLevel level = new MallMemberLevel();
        applyFields(level, request);
        return levelRepository.save(level).getId();
    }

    @Override
    public void update(Long levelId, MemberLevelSaveRequest request) {
        MallMemberLevel level = levelRepository.findById(levelId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "会员等级不存在"));
        if (levelRepository.existsByLevelNameAndIdNot(request.levelName(), levelId)) {
            throw new BusinessException(ErrorCode.DATA_CONFLICT, "等级名称已存在");
        }
        if (!level.getLevelSort().equals(request.levelSort())) {
            // 改顺序会影响"达到成长值后升到哪一级"的判定,而已有客户是按老顺序晋升的。
            // 本期不做自动重算,所以先明确拒绝而不是悄悄改掉
            long customers = customerRepository.countByMemberLevelId(levelId);
            if (customers > 0) {
                throw new BusinessException(ErrorCode.DATA_CONFLICT,
                        "该等级下已有 " + customers + " 位客户,调整顺序需要先确认升级规则");
            }
        }
        applyFields(level, request);
    }

    private void applyFields(MallMemberLevel level, MemberLevelSaveRequest request) {
        level.setLevelName(request.levelName());
        level.setLevelSort(request.levelSort());
        level.setGrowthThreshold(request.growthThreshold());
        // 折扣率本期不参与计算,但仍然校验取值范围,避免库里留下"不可能正确"的配置
        BigDecimal rate = request.discountRate();
        if (rate != null && (rate.signum() <= 0 || rate.compareTo(BigDecimal.ONE) > 0)) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "折扣率必须在 0 与 1 之间(如 0.95 表示 9.5 折)");
        }
        level.setDiscountRate(rate);
        level.setStatus(request.status() == null ? STATUS_ENABLED : request.status());
    }
}

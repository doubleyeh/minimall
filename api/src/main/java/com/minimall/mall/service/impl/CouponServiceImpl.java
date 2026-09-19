package com.minimall.mall.service.impl;

import com.minimall.api.mall.dto.ClientCouponView;
import com.minimall.api.mall.dto.CouponSaveRequest;
import com.minimall.api.mall.dto.CouponView;
import com.minimall.common.BusinessException;
import com.minimall.common.ErrorCode;
import com.minimall.common.PageResult;
import com.minimall.infra.tenant.TenantContext;
import com.minimall.mall.domain.MallCoupon;
import com.minimall.mall.domain.MallCouponRecord;
import com.minimall.mall.domain.QMallCoupon;
import com.minimall.mall.domain.repository.MallCouponRecordRepository;
import com.minimall.mall.domain.repository.MallCouponRepository;
import com.minimall.mall.infra.auth.ClientContext;
import com.minimall.mall.service.CouponService;
import com.querydsl.core.BooleanBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 优惠券实现(商城设计文档 3.10)。
 *
 * <p>领取路径上的两步缺一不可:①按 {@code perCustomerLimit} 校验"这个人还能不能领";
 * ②用条件更新占用名额。第二步是**防超发的唯一保证** —— 并发领券时两个请求都会通过第一步,
 * 只有把"还有名额"写进 UPDATE 的 WHERE 里才拦得住。
 */
@Service
@Transactional
public class CouponServiceImpl implements CouponService {

    private static final Logger log = LoggerFactory.getLogger(CouponServiceImpl.class);

    private static final int STATUS_ENABLED = 1;
    private static final int RECORD_UNUSED = 1;
    private static final int RECORD_USED = 2;
    private static final int RECORD_EXPIRED = 3;

    private final MallCouponRepository couponRepository;
    private final MallCouponRecordRepository couponRecordRepository;

    public CouponServiceImpl(MallCouponRepository couponRepository,
                             MallCouponRecordRepository couponRecordRepository) {
        this.couponRepository = couponRepository;
        this.couponRecordRepository = couponRecordRepository;
    }

    @Override
    public PageResult<CouponView> page(String couponName, Integer status, int pageNo, int pageSize) {
        QMallCoupon qCoupon = QMallCoupon.mallCoupon;
        BooleanBuilder where = new BooleanBuilder();
        if (couponName != null && !couponName.isBlank()) {
            where.and(qCoupon.couponName.contains(couponName));
        }
        if (status != null) {
            where.and(qCoupon.status.eq(status));
        }
        Page<MallCoupon> page = couponRepository.findAll(where,
                PageRequest.of(Math.max(pageNo - 1, 0), Math.max(pageSize, 1))
                        .withSort(Sort.by("id").descending()));
        List<CouponView> views = page.getContent().stream().map(this::toView).toList();
        return PageResult.of(page.getTotalElements(), views);
    }

    @Override
    public Long create(CouponSaveRequest request) {
        validateRequest(request);
        MallCoupon coupon = new MallCoupon();
        applyFields(coupon, request);
        coupon.setReceivedCount(0);
        return couponRepository.save(coupon).getId();
    }

    @Override
    public void update(Long couponId, CouponSaveRequest request) {
        MallCoupon coupon = load(couponId);
        validateRequest(request);
        if (request.totalCount() < coupon.getReceivedCount()) {
            // 总量不能改到已发出量以下:那会让"剩余名额"变成负数,后续领取全部失败
            throw new BusinessException(ErrorCode.PARAM_INVALID,
                    "发放总量不能小于已领取数量(" + coupon.getReceivedCount() + ")");
        }
        applyFields(coupon, request);
    }

    @Override
    public void changeStatus(Long couponId, Integer status) {
        if (status == null || (status != 0 && status != 1)) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "状态只能是 0(停用)或 1(启用)");
        }
        MallCoupon coupon = load(couponId);
        coupon.setStatus(status);
    }

    @Override
    public List<ClientCouponView> claimable() {
        LocalDateTime now = LocalDateTime.now();
        Long customerId = ClientContext.getCustomerId();
        List<MallCoupon> coupons = couponRepository.findClaimable(now);
        if (coupons.isEmpty()) {
            return List.of();
        }
        Map<Long, Long> claimedCount = customerId == null ? Map.of() : claimedCounts(customerId, coupons);
        return coupons.stream()
                .map(coupon -> new ClientCouponView(coupon.getId(), null, coupon.getCouponName(),
                        coupon.getCouponType(), coupon.getDiscountAmount(), coupon.getDiscountRate(),
                        coupon.getMinOrderAmount(), coupon.getValidEndTime(), null,
                        claimed(coupon, claimedCount.getOrDefault(coupon.getId(), 0L))))
                .toList();
    }

    @Override
    public List<ClientCouponView> mine(Integer status) {
        Long customerId = ClientContext.requireCustomerId();
        List<MallCouponRecord> records = couponRecordRepository.findByCustomerIdOrderByIdDesc(customerId);
        if (records.isEmpty()) {
            return List.of();
        }
        Map<Long, MallCoupon> couponById = new HashMap<>();
        couponRepository.findAllById(records.stream().map(MallCouponRecord::getCouponId).distinct().toList())
                .forEach(coupon -> couponById.put(coupon.getId(), coupon));
        LocalDateTime now = LocalDateTime.now();
        List<ClientCouponView> views = new ArrayList<>();
        for (MallCouponRecord record : records) {
            MallCoupon coupon = couponById.get(record.getCouponId());
            if (coupon == null) {
                continue;
            }
            // 已过期但定时任务还没跑到:读的时候修正为"已过期",否则用户会看到一张"未使用"的过期券
            int effectiveStatus = record.getStatus() == RECORD_UNUSED && coupon.getValidEndTime().isBefore(now)
                    ? RECORD_EXPIRED : record.getStatus();
            if (status != null && status != effectiveStatus) {
                continue;
            }
            views.add(new ClientCouponView(coupon.getId(), record.getId(), coupon.getCouponName(),
                    coupon.getCouponType(), coupon.getDiscountAmount(), coupon.getDiscountRate(),
                    coupon.getMinOrderAmount(), coupon.getValidEndTime(), effectiveStatus,
                    statusText(effectiveStatus)));
        }
        return views;
    }

    @Override
    public Long claim(Long couponId) {
        Long customerId = ClientContext.requireCustomerId();
        Long tenantId = TenantContext.getTenantId();
        MallCoupon coupon = load(couponId);
        LocalDateTime now = LocalDateTime.now();
        if (coupon.getStatus() == null || coupon.getStatus() != STATUS_ENABLED
                || coupon.getValidStartTime().isAfter(now) || coupon.getValidEndTime().isBefore(now)) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "优惠券不在可领取时间内");
        }
        long owned = couponRecordRepository.countByCouponIdAndCustomerId(couponId, customerId);
        if (owned >= coupon.getPerCustomerLimit()) {
            throw new BusinessException(ErrorCode.PARAM_INVALID,
                    "该优惠券每人限领 " + coupon.getPerCustomerLimit() + " 张");
        }
        // 先占名额:受影响行数为 0 说明已领完(并发下唯一可靠的判断)
        int claimed = couponRepository.claimOne(couponId, tenantId);
        if (claimed == 0) {
            throw new BusinessException(ErrorCode.DATA_CONFLICT, "优惠券已领完");
        }
        MallCouponRecord record = new MallCouponRecord();
        record.setCouponId(couponId);
        record.setCustomerId(customerId);
        record.setStatus(RECORD_UNUSED);
        record.setReceiveTime(now);
        Long recordId = couponRecordRepository.save(record).getId();
        log.info("优惠券领取成功 tenantId={} customerId={} couponId={}", tenantId, customerId, couponId);
        return recordId;
    }

    // ------------------------------------------------------------------ 内部

    private void validateRequest(CouponSaveRequest request) {
        if (request.validEndTime().isBefore(request.validStartTime())) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "有效期结束时间不能早于开始时间");
        }
        boolean discountType = request.couponType() != null && request.couponType() == MallCoupon.TYPE_DISCOUNT;
        if (discountType) {
            BigDecimal rate = request.discountRate();
            if (rate == null || rate.signum() <= 0 || rate.compareTo(BigDecimal.ONE) >= 0) {
                // 折扣率必须落在 (0,1):等于 1 是"不打折",大于 1 是加价,都说明配置写错了
                throw new BusinessException(ErrorCode.PARAM_INVALID, "折扣券的折扣率必须在 0 与 1 之间(如 0.9 表示 9 折)");
            }
        } else {
            if (request.discountAmount() == null || request.discountAmount().signum() <= 0) {
                throw new BusinessException(ErrorCode.PARAM_INVALID, "请填写大于 0 的减免金额");
            }
        }
    }

    private void applyFields(MallCoupon coupon, CouponSaveRequest request) {
        coupon.setCouponName(request.couponName());
        coupon.setCouponType(request.couponType());
        boolean discountType = request.couponType() != null && request.couponType() == MallCoupon.TYPE_DISCOUNT;
        // 按类型只保留一个字段,避免库里同时存在两个值导致"以哪个为准"的歧义
        coupon.setDiscountAmount(discountType ? null : request.discountAmount());
        coupon.setDiscountRate(discountType ? request.discountRate() : null);
        coupon.setMinOrderAmount(request.minOrderAmount());
        coupon.setTotalCount(request.totalCount());
        coupon.setPerCustomerLimit(request.perCustomerLimit());
        coupon.setValidStartTime(request.validStartTime());
        coupon.setValidEndTime(request.validEndTime());
        coupon.setStatus(request.status() == null ? STATUS_ENABLED : request.status());
    }

    private Map<Long, Long> claimedCounts(Long customerId, List<MallCoupon> coupons) {
        Map<Long, Long> result = new HashMap<>();
        for (MallCoupon coupon : coupons) {
            result.put(coupon.getId(),
                    couponRecordRepository.countByCouponIdAndCustomerId(coupon.getId(), customerId));
        }
        return result;
    }

    private String claimed(MallCoupon coupon, long owned) {
        if (owned >= coupon.getPerCustomerLimit()) {
            return "已领取";
        }
        if (coupon.getReceivedCount() != null && coupon.getTotalCount() != null
                && coupon.getReceivedCount() >= coupon.getTotalCount()) {
            return "已领完";
        }
        return "可领取";
    }

    private String statusText(int status) {
        return switch (status) {
            case RECORD_UNUSED -> "未使用";
            case RECORD_USED -> "已使用";
            case RECORD_EXPIRED -> "已过期";
            default -> "未知";
        };
    }

    private MallCoupon load(Long couponId) {
        return couponRepository.findById(couponId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "优惠券不存在"));
    }

    private CouponView toView(MallCoupon coupon) {
        return new CouponView(coupon.getId(), coupon.getCouponName(), coupon.getCouponType(),
                coupon.getDiscountAmount(), coupon.getDiscountRate(), coupon.getMinOrderAmount(),
                coupon.getTotalCount(), coupon.getReceivedCount(), coupon.getPerCustomerLimit(),
                coupon.getValidStartTime(), coupon.getValidEndTime(), coupon.getStatus());
    }
}

package com.minimall.mall.infra.pay;

import com.minimall.common.BusinessException;
import com.minimall.common.ErrorCode;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 元与分的换算:微信 V3 的金额一律是整数分,库里是两位小数的元。
 *
 * <p>放在 infra/pay 而不是订单金额工具里,是为了守住单向依赖(架构文档 3):
 * 依赖方向是 service → infra,反过来让 infra 去依赖 service 会被架构测试拦下。
 */
public final class WxPayAmounts {

    private static final int MONEY_SCALE = 2;

    private WxPayAmounts() {
    }

    /** 元转分:先按分四舍五入再取整;非正数与超出 int 范围都报业务错。 */
    public static int toCents(BigDecimal yuan) {
        if (yuan == null || yuan.signum() <= 0) {
            throw new BusinessException(ErrorCode.PAY_AMOUNT_INVALID, "金额必须大于 0");
        }
        try {
            return yuan.setScale(MONEY_SCALE, RoundingMode.HALF_UP).movePointRight(2).intValueExact();
        } catch (ArithmeticException ex) {
            throw new BusinessException(ErrorCode.PAY_AMOUNT_INVALID, "金额超出支付渠道上限");
        }
    }

    /** 分转元,固定两位小数。 */
    public static BigDecimal toYuan(int cents) {
        return BigDecimal.valueOf(cents).movePointLeft(2).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }
}

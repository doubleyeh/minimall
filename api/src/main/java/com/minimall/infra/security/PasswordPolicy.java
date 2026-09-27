package com.minimall.infra.security;

import com.minimall.common.BusinessException;
import com.minimall.common.ErrorCode;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 密码策略(架构文档 7.1.2):历史不可复用 + 有效期。
 *
 * <p><b>历史不可复用挡的是什么</b>:用户被要求改密时,最常见的应对是"在原密码后面加个 1"。
 * 那样改密只是走个流程,"初始密码泄露"这类风险并没有被消除。所以新密码不能与最近 N 个相同。
 *
 * <p><b>有效期默认关闭</b>({@code password-expire-days: 0}):开启后所有存量用户在下一次登录时
 * 都会被打到改密页 —— 这是产品决定,不该由代码替他定。要启用就改配置(见 application.yml)。
 *
 * <p>历史只存**哈希**(与密码本身同一套 bcrypt),存明文等于把"记住密码"的收益拱手让人。
 * 存法是一列逗号分隔的哈希(最近一次在最前),够用且不需要额外的表与迁移。
 */
@Component
public class PasswordPolicy {

    private static final String DELIMITER = ",";

    private final PasswordEncoder passwordEncoder;
    private final LoginProperties properties;

    public PasswordPolicy(PasswordEncoder passwordEncoder, LoginProperties properties) {
        this.passwordEncoder = passwordEncoder;
        this.properties = properties;
    }

    /**
     * 校验新密码是否可以接受:不能与当前密码相同,也不能与最近 N 个历史密码相同。
     *
     * @param currentHash 当前密码哈希
     * @param historyCsv  历史哈希(逗号分隔,最近一次在最前),可为空
     */
    public void ensureAcceptable(String currentHash, String historyCsv, String newPassword, String oldPassword) {
        if (passwordEncoder.matches(newPassword, currentHash)) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "新密码不能与原密码相同");
        }
        if (matchesAny(historyCsv, newPassword)) {
            throw new BusinessException(ErrorCode.PARAM_INVALID,
                    "新密码不能与最近 " + properties.passwordHistoryCount() + " 次用过的密码相同");
        }
        // 老密码当然也在历史里,单独比一次让提示更准确("与原密码相同"比"与历史重复"更好懂)
        if (oldPassword != null && oldPassword.equals(newPassword)) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "新密码不能与原密码相同");
        }
    }

    /**
     * 改密后记账:把**旧密码哈希**放进历史,只保留最近 N 个。
     *
     * @return 新的历史串(可直接写进实体)
     */
    public String historyAfterChange(String historyCsv, String oldHash) {
        List<String> history = new ArrayList<>();
        history.add(oldHash);
        for (String hash : split(historyCsv)) {
            if (history.size() >= properties.passwordHistoryCount()) {
                break;
            }
            history.add(hash);
        }
        return String.join(DELIMITER, history);
    }

    /** 密码是否已过期。未启用有效期时永远返回 false。 */
    public boolean isExpired(LocalDateTime pwdUpdateTime) {
        int expireDays = properties.passwordExpireDays();
        if (expireDays <= 0) {
            return false;
        }
        if (pwdUpdateTime == null) {
            // 从没改过密(比如老数据):开启有效期时一律要求改,不放过
            return true;
        }
        return pwdUpdateTime.plusDays(expireDays).isBefore(LocalDateTime.now());
    }

    private boolean matchesAny(String historyCsv, String rawPassword) {
        for (String hash : split(historyCsv)) {
            if (passwordEncoder.matches(rawPassword, hash)) {
                return true;
            }
        }
        return false;
    }

    private List<String> split(String historyCsv) {
        if (historyCsv == null || historyCsv.isBlank()) {
            return List.of();
        }
        return Arrays.stream(historyCsv.split(DELIMITER))
                .map(String::trim)
                .filter(hash -> !hash.isEmpty())
                .toList();
    }
}

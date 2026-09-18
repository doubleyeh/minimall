package com.minimall.service.sys.support;

import java.security.SecureRandom;

/**
 * 初始密码生成(架构文档 7.1.2)。
 *
 * <p>抽成公用工具的原因:建租户、新增用户、重置密码三处都要生成初始密码,
 * 各写一份迟早会走偏(比如某一处加了特殊字符、另一处没加),而"生成出来的密码过不了自己的复杂度校验"
 * 是个很尴尬的 bug。
 *
 * <p>字符集去掉了形近字符(0/O、1/l/I),避免初始密码在人工抄录时出错。
 * 前两位固定为"一个字母 + 一个数字",保证一定满足复杂度要求(字母 + 数字)。
 */
public final class PasswordGenerator {

    private static final String LETTERS = "abcdefghijkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ";
    private static final String DIGITS = "23456789";
    private static final String ALPHABET = LETTERS + DIGITS;
    private static final int DEFAULT_LENGTH = 12;

    private static final SecureRandom RANDOM = new SecureRandom();

    private PasswordGenerator() {
    }

    public static String generate() {
        return generate(DEFAULT_LENGTH);
    }

    public static String generate(int length) {
        if (length < 3) {
            throw new IllegalArgumentException("初始密码长度至少为3,当前=" + length);
        }
        StringBuilder password = new StringBuilder(length);
        password.append(LETTERS.charAt(RANDOM.nextInt(LETTERS.length())));
        password.append(DIGITS.charAt(RANDOM.nextInt(DIGITS.length())));
        for (int i = 2; i < length; i++) {
            password.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        }
        return password.toString();
    }
}

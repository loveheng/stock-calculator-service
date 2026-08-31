package com.zzh.stock_calculator.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.regex.Pattern;

/**
 * E2EE 用户服务密码学杂项工具（docs/e2ee-auth-backend-design.md §D.4.3 / §D.5）：无状态静态方法。
 *
 * @description 会话令牌 / OTP 的生成与哈希、常量时间比较、入参形态校验、邮箱归一化。
 *              邮箱归一化必须与前端约定一致（trim + 小写，《前端 spec》§5.2）。
 */
public final class AuthCryptoUtil {

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    /** authHash 固定形态：64 位小写 hex（同时保证 bcrypt 72 字节输入上限内，杜绝静默截断） */
    public static final Pattern AUTH_HASH = Pattern.compile("^[0-9a-f]{64}$");

    /** 宽松邮箱形态校验（只挡明显非法值，不做完整 RFC 校验） */
    public static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

    /** OTP 码型：6 位数字 */
    public static final Pattern OTP_CODE = Pattern.compile("^\\d{6}$");

    private AuthCryptoUtil() {
    }

    public static String sha256Hex(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return hex(md.digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    /** 256-bit 不透明会话令牌，base64url 无填充（43 字符） */
    public static String randomToken() {
        byte[] bytes = new byte[32];
        SECURE_RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** 6 位数字验证码（约 20-bit 熵，配合 10 分钟有效期 / 限次 / 限流足够，§D.5.2） */
    public static String randomOtp() {
        return String.format("%06d", SECURE_RANDOM.nextInt(1_000_000));
    }

    /** 常量时间比较（OTP 哈希比对、吊销集合比对用） */
    public static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    public static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }

    /** 邮箱归一化：trim + 小写（与前端 normalizeEmail 同一约定） */
    public static String normalizeEmail(String email) {
        return email == null ? "" : email.trim().toLowerCase();
    }
}

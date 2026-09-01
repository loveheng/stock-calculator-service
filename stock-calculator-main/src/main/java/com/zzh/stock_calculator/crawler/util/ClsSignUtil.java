package com.zzh.stock_calculator.crawler.util;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;
import java.util.TreeMap;

public class ClsSignUtil {
    public static String getSign(Map<String, Object> params) {
        if (params == null || params.isEmpty()) {
            return "";
        }

        // 1. 按 Key 字典序升序排序
        Map<String, Object> sortedParams = new TreeMap<>(params);
        StringBuilder sb = new StringBuilder();

        for (Map.Entry<String, Object> entry : sortedParams.entrySet()) {
            String key = entry.getKey();
            Object val = entry.getValue();

            if (!"sign".equalsIgnoreCase(key) && val != null) {
                if (sb.length() > 0) {
                    sb.append("&");
                }
                sb.append(key).append("=").append(val);
            }
        }

        // 2. SHA-1 -> MD5 双重哈希
        String sha1Hex = sha1(sb.toString());
        return md5(sha1Hex);
    }

    private static String sha1(String text) {
        return hash(text, "SHA-1");
    }

    private static String md5(String text) {
        return hash(text, "MD5");
    }

    private static String hash(String input, String algorithm) {
        try {
            MessageDigest md = MessageDigest.getInstance(algorithm);
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder();
            for (byte b : digest) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }

}

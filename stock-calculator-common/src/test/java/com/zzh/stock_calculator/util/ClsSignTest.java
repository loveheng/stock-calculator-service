package com.zzh.stock_calculator.util;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


import java.time.Instant;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;

@Slf4j
public class ClsSignTest {

    private static final Logger log = LoggerFactory.getLogger(ClsSignTest.class);

    @Test
    public  void test() {
        try {

            // 1. 构造请求参数
            long epochSecond = Instant.now().getEpochSecond();
            log.info("当前时间："+epochSecond);

            epochSecond=1787816196;

            Map<String, Object> params = new TreeMap<>();
            params.put("app", "CailianpressWeb");
            params.put("last_time", epochSecond); // 当前时间戳（秒）
            params.put("os", "web");
            params.put("refresh_type", 1);
            params.put("rn", 5);
            params.put("sv", "8.7.9");

            // 2. 生成签名
            String sign = ClsSignUtil.getSign(params);
            params.put("sign", sign);

            // 3. 拼接 URL 查询字符串
            StringBuilder queryString = new StringBuilder();
            for (Map.Entry<String, Object> entry : params.entrySet()) {
                if (queryString.length() > 0) queryString.append("&");
                queryString.append(entry.getKey()).append("=").append(entry.getValue());
            }

            log.info(queryString.toString());

            assertEquals("4451ead5acd001ed12cf05ac6b3386b9",sign);

        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}

package com.zzh.stock_calculator.auth.dto;
import lombok.Data;
import lombok.ToString;

/**
 * 登录请求（《设计》§D.4.2 端点 2）。
 *
 * @description ttlDays 为"记住登录"时长（7 / 30，缺省 7）；password 为 authHash，toString 脱敏。
 */
@Data
public class LoginRequest {

    private String email;

    @ToString.Exclude
    private String password;

    private Integer ttlDays;
}

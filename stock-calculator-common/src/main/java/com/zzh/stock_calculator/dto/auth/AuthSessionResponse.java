package com.zzh.stock_calculator.dto.auth;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 会话签发响应（《设计》§D.4.2）：register / login / recovery verify / confirm 共用。
 *
 * @description hasProfile 仅 login 填充（驱动前端缺行分支：孤儿引导 / 补传，等价 maybeSingle 语义）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AuthSessionResponse {

    private UUID userId;

    private String token;

    private OffsetDateTime expiresAt;

    private Boolean hasProfile;
}

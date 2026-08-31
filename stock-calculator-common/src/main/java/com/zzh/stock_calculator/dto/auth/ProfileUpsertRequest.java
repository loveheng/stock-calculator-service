package com.zzh.stock_calculator.dto.auth;

import lombok.Data;

/**
 * 密文档案 upsert 请求（《设计》§D.4.2 端点 5）：四密文，均为客户端封装产物，服务端只存不解。
 *
 * @description 配合 If-Match 头使用；字段 Base64 合法性在服务端校验（B3 加固 §4.2）。
 */
@Data
public class ProfileUpsertRequest {

    private String passwordPayload;

    private String passwordIv;

    private String recoveryPayload;

    private String recoveryIv;
}

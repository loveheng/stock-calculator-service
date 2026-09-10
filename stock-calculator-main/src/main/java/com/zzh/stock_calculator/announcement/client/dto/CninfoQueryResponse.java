package com.zzh.stock_calculator.announcement.client.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * CNINFO /new/hisAnnouncement/query 响应（S3 实证）：
 * announcements 可为 null；分页以 hasMore 为准（totalpages 不可信）；
 * adjunctSize 单位 KB；announcementTime = epoch ms（≈ 北京时间公告日 00:00）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class CninfoQueryResponse {

    private Integer totalRecordNum;
    private Boolean hasMore;
    private Integer totalpages;
    private List<CninfoAnnouncement> announcements;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class CninfoAnnouncement {
        private String announcementId;
        private String announcementTitle;
        private String adjunctUrl;
        /** 单位 KB（S3 实证） */
        private Integer adjunctSize;
        /** epoch ms ≈ 北京 00:00（S3 实证） */
        private Long announcementTime;
        private String secCode;
        private String secName;
        private String orgId;
        /** 恒为空串（S3 实证），字段留档对齐 */
        private String announcementContent;
    }
}

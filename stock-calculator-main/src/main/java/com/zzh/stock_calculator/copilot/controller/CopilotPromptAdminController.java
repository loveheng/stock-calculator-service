package com.zzh.stock_calculator.copilot.controller;

import com.zzh.stock_calculator.common.ApiResponse;
import com.zzh.stock_calculator.copilot.dto.CopilotDtos.PromptTemplateHistoryItem;
import com.zzh.stock_calculator.copilot.dto.CopilotDtos.PromptTemplateItem;
import com.zzh.stock_calculator.copilot.dto.CopilotDtos.PromptUpsertRequest;
import com.zzh.stock_calculator.copilot.entity.CopilotPromptTemplate;
import com.zzh.stock_calculator.copilot.entity.CopilotPromptTemplateHistory;
import com.zzh.stock_calculator.copilot.service.CopilotPromptAdminService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Copilot Prompt 模版在线管理控制层（5 端点，鉴权复用 AuthInterceptor：/api/copilot/** 已挂拦截）。
 * GET    /api/copilot/prompt/templates              - 全量列表（当前态）
 * GET    /api/copilot/prompt/templates/{tag}        - 单条回显
 * PUT    /api/copilot/prompt/templates/{tag}        - 新增/更新（改后即时生效，自动留痕）
 * DELETE /api/copilot/prompt/templates/{tag}        - 删除（自动留痕，该标签回落下一级）
 * GET    /api/copilot/prompt/templates/{tag}/history - 历次修改记录（rev 从新到旧）
 *
 * <p>版本控制：每次 PUT/DELETE 均写历史表（rev 递增 + 操作类型 + 内容快照）；
 * 回滚 = 取历史某条 content 重新 PUT（生成新 rev，审计链不断）。
 * 路径变量 tag 含冒号（如 home:short_term）合法，冒号属 RFC 3986 path 字符无需编码。</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/copilot/prompt")
@RequiredArgsConstructor
public class CopilotPromptAdminController {

    private final CopilotPromptAdminService adminService;

    // ==================== GET /templates ====================

    /** 全量模版列表（含内容与时间戳，供前端管理页列表展示） */
    @GetMapping("/templates")
    public ApiResponse<List<PromptTemplateItem>> list() {
        try {
            return ApiResponse.success(adminService.list().stream()
                    .map(this::toItem)
                    .toList());
        } catch (Exception e) {
            log.error("Failed to list prompt templates: ", e);
            return ApiResponse.fail(500, "获取模版列表失败: " + e.getMessage());
        }
    }

    // ==================== GET /templates/{tag} ====================

    /** 单条模版回显（编辑表单预填） */
    @GetMapping("/templates/{tag}")
    public ApiResponse<PromptTemplateItem> get(@PathVariable("tag") String tag) {
        try {
            return adminService.get(tag)
                    .<ApiResponse<PromptTemplateItem>>map(t -> ApiResponse.success(toItem(t)))
                    .orElseGet(() -> ApiResponse.fail(404, "模版不存在: 该标签未配置"));
        } catch (IllegalArgumentException e) {
            log.warn("Bad Request: {}", e.getMessage());
            return ApiResponse.fail(400, e.getMessage());
        } catch (Exception e) {
            log.error("Failed to get prompt template: ", e);
            return ApiResponse.fail(500, "获取模版失败: " + e.getMessage());
        }
    }

    // ==================== PUT /templates/{tag} ====================

    /** 新增/更新模版，改 DB（同事务留痕）后同步 Redis 即时生效 */
    @PutMapping("/templates/{tag}")
    public ApiResponse<PromptTemplateItem> upsert(@PathVariable("tag") String tag,
                                                  @RequestBody PromptUpsertRequest req) {
        try {
            return ApiResponse.success(toItem(adminService.upsert(tag, req.getContent())));
        } catch (IllegalArgumentException e) {
            log.warn("Bad Request: {}", e.getMessage());
            return ApiResponse.fail(400, e.getMessage());
        } catch (Exception e) {
            log.error("Failed to upsert prompt template: ", e);
            return ApiResponse.fail(500, "保存模版失败: " + e.getMessage());
        }
    }

    // ==================== DELETE /templates/{tag} ====================

    /** 删除模版：删 DB 行（留痕记录被删内容）+ DEL Redis key，该标签即回落下一级 */
    @DeleteMapping("/templates/{tag}")
    public ApiResponse<Void> delete(@PathVariable("tag") String tag) {
        try {
            adminService.delete(tag);
            return ApiResponse.success(null);
        } catch (IllegalArgumentException e) {
            log.warn("Bad Request: {}", e.getMessage());
            return ApiResponse.fail(400, e.getMessage());
        } catch (Exception e) {
            log.error("Failed to delete prompt template: ", e);
            return ApiResponse.fail(500, "删除模版失败: " + e.getMessage());
        }
    }

    // ==================== GET /templates/{tag}/history ====================

    /** 历次修改记录（rev 从新到旧；content 为该次操作后的内容，DELETE 记录被删内容） */
    @GetMapping("/templates/{tag}/history")
    public ApiResponse<List<PromptTemplateHistoryItem>> history(@PathVariable("tag") String tag) {
        try {
            return ApiResponse.success(adminService.listHistory(tag).stream()
                    .map(this::toHistoryItem)
                    .toList());
        } catch (IllegalArgumentException e) {
            log.warn("Bad Request: {}", e.getMessage());
            return ApiResponse.fail(400, e.getMessage());
        } catch (Exception e) {
            log.error("Failed to list prompt template history: ", e);
            return ApiResponse.fail(500, "获取修改历史失败: " + e.getMessage());
        }
    }

    // ==================== Helpers ====================

    private PromptTemplateItem toItem(CopilotPromptTemplate t) {
        return PromptTemplateItem.builder()
                .id(t.getId())
                .tag(t.getTag())
                .content(t.getContent())
                .ctime(t.getCtime())
                .mtime(t.getMtime())
                .build();
    }

    private PromptTemplateHistoryItem toHistoryItem(CopilotPromptTemplateHistory h) {
        return PromptTemplateHistoryItem.builder()
                .id(h.getId())
                .tag(h.getTag())
                .rev(h.getRev())
                .content(h.getContent())
                .operation(h.getOperation())
                .ctime(h.getCtime())
                .build();
    }
}

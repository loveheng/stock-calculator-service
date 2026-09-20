package com.zzh.stock_calculator.mcp.kb;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 订阅源管理端点（本地自用无鉴权）：注册即灌入 / 停更保数据移除 / 清单巡检。
 */
@RestController
@RequestMapping("/admin/source")
@RequiredArgsConstructor
public class KbSourceAdminController {

    private final KbSourceService sourceService;

    @PostMapping
    public Map<String, Object> register(@RequestParam String name,
                                        @RequestParam String type,
                                        @RequestParam String location) {
        return sourceService.register(name, type, location);
    }

    @GetMapping
    public List<Map<String, Object>> list() {
        return sourceService.list();
    }

    @DeleteMapping("/{name}")
    public Map<String, Object> remove(@PathVariable String name) {
        return sourceService.remove(name);
    }

    /** 手动刷新单源（不等轮询周期）：rss=拉增量，text=整源重灌 */
    @PostMapping("/{name}/refresh")
    public Map<String, Object> refresh(@PathVariable String name) {
        return sourceService.refresh(name);
    }
}

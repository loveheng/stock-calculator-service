package com.zzh.stock_calculator.vision.service.impl;

import com.zzh.stock_calculator.vision.dto.StockCandidate;
import com.zzh.stock_calculator.vision.service.StockCodeResolver;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 腾讯 Smartbox 联想搜索股票代码补全器（拍板决策：第一期仅对接 Smartbox，
 * 旧名/改名兜底走前端人工流程，不引入 crawler 股票字典跨域门面）。
 *
 * <p>接口事实（2026-09 实测）：GET /s3/?t=all&q={UTF-8 urlencode 名称}；
 * 响应 v_hint="sh~600745~*ST闻泰~stwt~GP-A^..."（行间 ^、行内 ~、非 ASCII 以反斜杠 u + 4 位十六进制转义），
 * 无结果为 v_hint="N"。GBK 编码查询无效，必须 UTF-8（经 RestClient URI 变量标准编码）。</p>
 *
 * <p>容错设计（fail-open）：任何查询异常记 warn 后按零候选返回，绝不阻断草稿主流程；
 * 瞬时失败不落缓存（避免网络抖动把「零匹配」误缓存），真实零匹配才缓存防打爆免费接口。</p>
 */
@Slf4j
@Component
public class SmartBoxStockCodeResolver implements StockCodeResolver {

    private static final String DEFAULT_SEARCH_URL = "https://smartbox.gtimg.cn/s3/?t=all&q={q}";

    /** 响应中的「反斜杠 + u + 4 位十六进制」JS 转义 */
    private static final Pattern UNICODE_ESCAPE = Pattern.compile("\\\\u([0-9a-fA-F]{4})");

    /** 只保留沪/深两个市场（hk/us 重名候选直接排除，如「中国银行」会同时返回 hk03988） */
    private static final Set<String> A_SHARE_MARKETS = Set.of("sh", "sz");

    private final RestClient restClient;
    private final String searchUrl;

    /** 查询名 -> 候选列表（进程内缓存；A股简称量级有限，免费接口无需淘汰策略） */
    private final ConcurrentHashMap<String, List<StockCandidate>> cache = new ConcurrentHashMap<>();

    /** 多构造器场景必须显式指定 Spring 装配入口，否则回退无参构造导致启动失败 */
    @Autowired
    public SmartBoxStockCodeResolver(RestClient commonRestClient) {
        this(commonRestClient, DEFAULT_SEARCH_URL);
    }

    /** 测试专用：可注入桩服务的搜索地址 */
    SmartBoxStockCodeResolver(RestClient restClient, String searchUrl) {
        this.restClient = restClient;
        this.searchUrl = searchUrl;
    }

    @Override
    public List<StockCandidate> search(String nameKeyword) {
        String keyword = sanitize(nameKeyword, false);
        if (keyword.isEmpty()) {
            return List.of();
        }
        List<StockCandidate> result = cache.computeIfAbsent(keyword, this::queryRemote);
        return result != null ? result : List.of();
    }

    /**
     * 查询主路径（保守清洗）零匹配时，降级激进清洗重试一次
     * （OCR 名称常带 *ST 前缀的 * 、.SH 后缀等干扰字符，如 *ST闻泰 直查无结果而 闻泰 可命中）。
     */
    private List<StockCandidate> queryRemote(String keyword) {
        List<StockCandidate> candidates = fetch(keyword);
        if (candidates != null && candidates.isEmpty()) {
            String aggressive = sanitize(keyword, true);
            if (!aggressive.isEmpty() && !aggressive.equals(keyword)) {
                candidates = fetch(aggressive);
            }
        }
        if (candidates == null) {
            // 瞬时失败：不缓存，下次调用重试
            return null;
        }
        if (candidates.isEmpty()) {
            log.info("Smartbox 未匹配到股票代码 (keyword={})", keyword);
        }
        return candidates;
    }

    /** 单次查询；null=瞬时失败（网络/HTTP 错误），空列表=真实零匹配 */
    private List<StockCandidate> fetch(String keyword) {
        try {
            String body = restClient.get()
                    .uri(searchUrl, keyword)
                    .retrieve()
                    .body(String.class);
            List<StockCandidate> parsed = parse(body);
            log.debug("Smartbox 查询完成 (keyword={}, candidates={})", keyword, parsed.size());
            return parsed;
        } catch (Exception e) {
            log.warn("Smartbox 查询失败，按零候选处理 (keyword={}): {}", keyword, e.getMessage());
            return null;
        }
    }

    /**
     * 解析 v_hint 响应：行间 ^ 分隔、行内 ~ 分隔（市场~代码~名称~拼音~类型）。
     * 过滤：仅保留 sh/sz 市场且 6 位数字代码的 A 股候选（hk/us 代码带市场前缀或非 6 位）。
     */
    private List<StockCandidate> parse(String body) {
        if (!StringUtils.hasText(body)) {
            return List.of();
        }
        int start = body.indexOf('"');
        int end = body.lastIndexOf('"');
        if (start < 0 || end <= start) {
            return List.of();
        }
        String payload = decodeUnicodeEscapes(body.substring(start + 1, end));
        if (!StringUtils.hasText(payload) || "N".equals(payload)) {
            return List.of();
        }
        List<StockCandidate> candidates = new ArrayList<>();
        for (String row : payload.split("\\^")) {
            String[] fields = row.split("~");
            if (fields.length < 5) {
                continue;
            }
            String market = fields[0].trim();
            String code = fields[1].trim();
            if (!A_SHARE_MARKETS.contains(market) || !code.matches("\\d{6}")) {
                continue;
            }
            candidates.add(new StockCandidate(market, code, fields[2].trim(), fields[4].trim()));
        }
        return List.copyOf(candidates);
    }

    /**
     * 名称清洗：保守 = 去空白与市场后缀（.SH/.SZ 等）；激进 = 再去除一切字母数字汉字之外的字符
     * （含 *ST 前缀的 *，Smartbox 对 * 开头的查询直接返回 N）。
     */
    private static String sanitize(String keyword, boolean aggressive) {
        if (!StringUtils.hasText(keyword)) {
            return "";
        }
        String cleaned = keyword.trim()
                .replaceAll("(?i)\\.(sh|sz|ss|bj)$", "")
                .replaceAll("\\s+", "");
        if (aggressive) {
            cleaned = cleaned.replaceAll("[^\\p{L}\\p{N}]", "");
        }
        return cleaned;
    }

    /** v_hint 响应中的「反斜杠 + u + 4 位十六进制」转义还原为真实字符（响应体纯 ASCII，无需关心传输字符集） */
    private static String decodeUnicodeEscapes(String text) {
        if (!text.contains("\\u")) {
            return text;
        }
        StringBuilder sb = new StringBuilder(text.length());
        Matcher matcher = UNICODE_ESCAPE.matcher(text);
        while (matcher.find()) {
            matcher.appendReplacement(sb,
                    Matcher.quoteReplacement(String.valueOf((char) Integer.parseInt(matcher.group(1), 16))));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }
}

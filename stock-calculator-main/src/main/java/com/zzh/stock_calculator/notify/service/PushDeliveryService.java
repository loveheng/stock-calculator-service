package com.zzh.stock_calculator.notify.service;

import com.zzh.stock_calculator.notify.entity.PushMessage;
import com.zzh.stock_calculator.notify.entity.PushSubscription;
import com.zzh.stock_calculator.notify.repository.PushMessageRepository;
import com.zzh.stock_calculator.notify.repository.PushSubscriptionRepository;
import lombok.extern.slf4j.Slf4j;
import nl.martijndwars.webpush.Notification;
import nl.martijndwars.webpush.PushService;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.security.Security;
import java.util.List;

/**
 * Web Push 投递：VAPID 签名 + RFC 8291 加密发送。
 *
 * @description 库 nl.martijndwars:web-push 要求 BouncyCastle 作为最高优先级 Provider
 *              （JCE 默认 Provider 不含 aes128gcm 所需 ECDH/HKDF 组合），故静态注册。
 *              失效订阅（推送服务回 404/410）当场物理删除——浏览器注销后不会复用 endpoint，
 *              保留只会让后续投递持续撞死链接。
 * @author 开发团队
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "push.enabled", havingValue = "true")
public class PushDeliveryService {

    private final PushSubscriptionService subscriptionService;
    private final PushMessageRepository messageRepository;
    private final PushSubscriptionRepository repository;
    private final PushService pushService = new PushService();
    @Value("${push.public-key}")
    private String publicKey;

    @Value("${push.private-key}")
    private String privateKey;

    @Value("${push.subject}")
    private String subject;

    public PushDeliveryService(PushSubscriptionService subscriptionService,
                               PushMessageRepository messageRepository,
                               PushSubscriptionRepository repository) {
        this.subscriptionService = subscriptionService;
        this.messageRepository = messageRepository;
        this.repository = repository;
    }

    @PostConstruct
    void init() throws Exception {
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.insertProviderAt(new BouncyCastleProvider(), 1);
        }
        pushService.setPublicKey(publicKey);
        pushService.setPrivateKey(privateKey);
        pushService.setSubject(subject);
        log.info("[push] PushDeliveryService 就绪 subject={}", subject);
    }

    /**
     * 投递一条通知：先落库（漏达补拉通道），再向全部订阅尽力推送。
     * 两通道共用同一条消息——推送失败/无订阅时，用户打开 PWA 仍能从消息面板拉到。
     *
     * @param title 通知标题
     * @param body  通知正文
     * @param url   点击通知后打开的页面路径（前端 SW notificationclick / 消息面板跳转用）
     * @return 推送成功触达的订阅数（0 不代表用户没收到——消息已落库可拉取）
     */
    /** 僵尸订阅判定窗口：90 天无成功触达即清（评估结论 #3） */
    private static final java.time.Duration STALE_WINDOW = java.time.Duration.ofDays(90);

    /** 合并窗口缓冲单元：一条待投递消息的轻量载体（title/body/url 三元组） */
    public record PendingMessage(String title, String body, String url) { }

    /**
     * 合并投递（PushCoalescingService 窗口冲刷调用）：批量逐条落库（通知中心明细不缩水），
     * Web Push 只发一条——标题取首条，正文逐行列出（单条消息则与 pushToUser 等价）。
     */
    public int pushMerged(String userId, List<PendingMessage> messages) {
        for (PendingMessage m : messages) {
            messageRepository.save(PushMessage.builder()
                    .userId(userId).title(m.title()).body(m.body()).url(m.url()).build());
        }
        PendingMessage first = messages.get(0);
        String title = messages.size() == 1 ? first.title()
                : first.title() + "（" + messages.size() + " 条合并）";
        StringBuilder bodyBuilder = new StringBuilder();
        for (PendingMessage m : messages) {
            if (bodyBuilder.length() > 0) {
                bodyBuilder.append('\n');
            }
            bodyBuilder.append(m.body());
        }
        return deliver(userId, title, bodyBuilder.toString(), first.url());
    }

    public int pushToUser(String userId, String title, String body, String url) {
        // 1) 落库（不依赖订阅存在）：推送是尽力触达，打开 PWA 拉未读是兜底
        messageRepository.save(PushMessage.builder()
                .userId(userId).title(title).body(body).url(url).build());
        return deliver(userId, title, body, url);
    }

    /** 落库后的推送段（惰性清僵尸订阅 + 逐订阅尽力触达），pushToUser/pushMerged 共用 */
    private int deliver(String userId, String title, String body, String url) {
        // 2) 惰性清理僵尸订阅（90 天无成功触达；顺带执行，无独立定时任务）
        int pruned = repository.pruneStale(java.time.OffsetDateTime.now().minus(STALE_WINDOW));
        if (pruned > 0) {
            log.info("[push] 僵尸订阅清理删除 {} 条（last_success_at/created_at 早于 {} 天前）", pruned, STALE_WINDOW.toDays());
        }

        // 3) 尽力推送
        List<PushSubscription> subs = subscriptionService.listByUser(userId);
        int ok = 0;
        for (PushSubscription sub : subs) {
            if (sendOne(sub, title, body, url)) {
                ok++;
            }
        }
        log.info("[push] 消息已落库并投递 userId={} 订阅 {}/{} 成功", userId, ok, subs.size());
        return ok;
    }

    private boolean sendOne(PushSubscription sub, String title, String body, String url) {
        try {
            // payload 走轻量 JSON 拼接（title/body/url 均为服务端组装文本，无用户注入面）
            String payload = "{\"title\":\"" + escape(title) + "\",\"body\":\"" + escape(body)
                    + "\",\"url\":\"" + escape(url) + "\"}";
            Notification notification = new Notification(
                    sub.getEndpoint(), sub.getP256dh(), sub.getAuth(), payload.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            pushService.send(notification);
            // 成功触达即刷新活性时间戳（僵尸订阅判定依据，pruneStale 用）
            repository.touchSuccess(sub.getEndpoint());
            return true;
        } catch (Exception e) {
            // 404/410 = 订阅失效（用户清除浏览器数据/重新授权），物理删除防死链累积
            String msg = String.valueOf(e.getMessage());
            if (msg.contains("404") || msg.contains("410")) {
                log.info("[push] 订阅已失效，移除 endpoint 尾8位=...{}",
                        sub.getEndpoint().substring(sub.getEndpoint().length() - 8));
                subscriptionService.unsubscribe(sub.getEndpoint());
            } else {
                log.warn("[push] 投递失败 endpoint 尾8位=...{}: {}",
                        sub.getEndpoint().substring(sub.getEndpoint().length() - 8), msg);
            }
            return false;
        }
    }

    /** JSON 字符串最小转义 */
    private static String escape(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }
}

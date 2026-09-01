package com.zzh.stock_calculator.auth.service;
import com.zzh.stock_calculator.common.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

/**
 * OTP 邮件发送（docs/e2ee-auth-backend-design.md §D.5.2）。
 *
 * @description 虚拟线程已启用，阻塞发送无需异步包装；邮件正文即验证码本体
 *              （等价前端 spec §11 "{{ .Token }}" 部署前提，改由后端内置模板承担）。
 *              JavaMailSender 由 spring-boot-starter-mail 自动装配，未配置 SMTP 时发送即报业务异常。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.auth.enabled", havingValue = "true")
public class MailService {

    private final ObjectProvider<JavaMailSender> mailSenderProvider;

    /** 发件人（docker-compose 注入 MAIL_FROM；为空时由 SMTP 服务器默认值兜底） */
    @Value("${MAIL_FROM:}")
    private String from;

    public void sendOtpCode(String toEmail, String code) {
        JavaMailSender sender = mailSenderProvider.getIfAvailable();
        if (sender == null) {
            log.error("mail sender not configured, check SMTP_* environment variables");
            throw new BusinessException(500, "邮件服务未配置，请联系管理员检查 SMTP 环境变量");
        }
        SimpleMailMessage message = new SimpleMailMessage();
        if (from != null && !from.isBlank()) {
            message.setFrom(from);
        }
        message.setTo(toEmail);
        message.setSubject("【股票计算助手】找回密码验证码");
        message.setText("您的验证码是：" + code + "\n\n"
                + "验证码 10 分钟内有效，单次使用。\n"
                + "若非本人操作，请忽略本邮件。");
        try {
            sender.send(message);
            log.info("otp mail sent, email={}", maskEmail(toEmail));
        } catch (Exception e) {
            log.error("otp mail send failed, email={}", maskEmail(toEmail), e);
            throw new BusinessException(500, "验证码邮件发送失败，请稍后重试");
        }
    }

    private String maskEmail(String email) {
        int at = email.indexOf('@');
        if (at <= 0) {
            return "***";
        }
        return email.charAt(0) + "***" + email.substring(at);
    }
}

package com.trading.tradeapi.config;

import com.trading.tradeapi.service.EmailConfigurationService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;

import java.nio.charset.StandardCharsets;
import java.util.Properties;

/**
 * The mail sender, built from the same SMTP_* values as the auth service but
 * cleaned first (see {@link EmailConfigurationService}): Spring's own
 * auto-configuration would pass a quoted host or user and a spaced app password
 * straight to the server, and Gmail refuses both.
 *
 * <p>Only created when a host is set; without one, account notices are logged
 * instead and the admin console reports that no email was delivered.
 */
@Configuration
@ConditionalOnExpression("!'${spring.mail.host:}'.trim().isEmpty()")
public class MailConfig {

    @Bean
    public JavaMailSender javaMailSender(
            @Value("${spring.mail.host}") String host,
            @Value("${spring.mail.port:587}") String port,
            @Value("${spring.mail.username:}") String username,
            @Value("${spring.mail.password:}") String password) {
        JavaMailSenderImpl sender = new JavaMailSenderImpl();
        sender.setHost(EmailConfigurationService.unquote(host));
        sender.setPort(Integer.parseInt(EmailConfigurationService.unquote(port)));
        String user = EmailConfigurationService.unquote(username);
        if (!user.isBlank()) {
            sender.setUsername(user);
            sender.setPassword(EmailConfigurationService.normalisePassword(password));
        }
        sender.setDefaultEncoding(StandardCharsets.UTF_8.name());

        Properties props = sender.getJavaMailProperties();
        boolean implicitTls = sender.getPort() == 465;
        props.put("mail.smtp.auth", String.valueOf(!user.isBlank()));
        // 465 is implicit TLS; anything else upgrades with STARTTLS, as the auth service does.
        props.put("mail.smtp.ssl.enable", String.valueOf(implicitTls));
        props.put("mail.smtp.starttls.enable", String.valueOf(!implicitTls));
        props.put("mail.smtp.starttls.required", String.valueOf(!implicitTls));
        // A slow mail server must not hold an admin request open indefinitely.
        props.put("mail.smtp.connectiontimeout", "10000");
        props.put("mail.smtp.timeout", "10000");
        props.put("mail.smtp.writetimeout", "10000");
        return sender;
    }
}

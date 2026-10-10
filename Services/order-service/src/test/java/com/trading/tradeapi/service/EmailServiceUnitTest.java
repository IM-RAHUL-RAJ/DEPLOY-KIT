package com.trading.tradeapi.service;

import com.trading.tradeapi.admin.AdminNotifications;
import com.trading.tradeapi.config.MailConfig;
import com.trading.tradeapi.dto.AdminUserDto;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.test.util.ReflectionTestUtils;

import jakarta.mail.internet.InternetAddress;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class EmailServiceUnitTest {

    private static EmailConfigurationService config(String host, String from, String username) {
        EmailConfigurationService config = new EmailConfigurationService();
        ReflectionTestUtils.setField(config, "mailHost", host);
        ReflectionTestUtils.setField(config, "fromAddress", from);
        ReflectionTestUtils.setField(config, "username", username);
        return config;
    }

    @Nested
    @DisplayName("EmailConfigurationService")
    class Configuration {

        @Test
        @DisplayName("strips the quotes a shared .env leaves on SMTP_FROM")
        void stripsQuotesFromTheSender() throws Exception {
            EmailConfigurationService c = config("smtp.gmail.com", "\"Akatsuki <no-reply@example.com>\"", "");

            assertThat(c.getFromAddress()).isEqualTo("Akatsuki <no-reply@example.com>");
            // The cleaned value is a real address JavaMail accepts.
            InternetAddress parsed = new InternetAddress(c.getFromAddress(), true);
            assertThat(parsed.getAddress()).isEqualTo("no-reply@example.com");
            assertThat(parsed.getPersonal()).isEqualTo("Akatsuki");
        }

        @Test
        @DisplayName("strips single quotes too, and surrounding whitespace")
        void stripsSingleQuotes() {
            assertThat(EmailConfigurationService.unquote("  'a@b.com'  ")).isEqualTo("a@b.com");
            assertThat(EmailConfigurationService.unquote("plain")).isEqualTo("plain");
            assertThat(EmailConfigurationService.unquote("\"mismatched'")).isEqualTo("\"mismatched'");
            assertThat(EmailConfigurationService.unquote(null)).isEmpty();
        }

        @Test
        @DisplayName("removes the spaces Google shows in an app password")
        void normalisesAppPassword() {
            assertThat(EmailConfigurationService.normalisePassword("abcd efgh ijkl mnop")).isEqualTo("abcdefghijklmnop");
            assertThat(EmailConfigurationService.normalisePassword("\"abcd efgh\"")).isEqualTo("abcdefgh");
        }

        @Test
        @DisplayName("falls back to the SMTP username when no From is set")
        void fallsBackToUsername() {
            EmailConfigurationService c = config("smtp.gmail.com", "", "sender@example.com");
            assertThat(c.getFromAddress()).isEqualTo("sender@example.com");
            assertThat(c.isConfigured()).isTrue();
        }

        @Test
        @DisplayName("is not configured without a host, or with neither From nor username")
        void notConfigured() {
            assertThat(config("", "a@b.com", "").isConfigured()).isFalse();
            assertThat(config("\"\"", "a@b.com", "").isConfigured()).isFalse();
            assertThat(config("smtp.gmail.com", "", "").isConfigured()).isFalse();
        }
    }

    @Nested
    @DisplayName("EmailService")
    class Sending {

        @Test
        @DisplayName("sends the suspension notice from the cleaned sender")
        void sendsSuspensionWithCleanSender() {
            JavaMailSender sender = mock(JavaMailSender.class);
            EmailService email = new EmailService(Optional.of(sender),
                    config("smtp.gmail.com", "\"Akatsuki <no-reply@example.com>\"", ""));

            boolean sent = email.sendAccountSuspensionEmail("trader@example.com", "Ada", "review");

            assertThat(sent).isTrue();
            ArgumentCaptor<SimpleMailMessage> message = ArgumentCaptor.forClass(SimpleMailMessage.class);
            verify(sender).send(message.capture());
            assertThat(message.getValue().getFrom()).isEqualTo("Akatsuki <no-reply@example.com>");
            assertThat(message.getValue().getTo()).containsExactly("trader@example.com");
            assertThat(message.getValue().getSubject()).contains("Suspended");
            assertThat(message.getValue().getText()).contains("Hello Ada").contains("Reason: review");
        }

        @Test
        @DisplayName("sends the approval notice with the account, its currency and the username")
        void sendsApproval() {
            JavaMailSender sender = mock(JavaMailSender.class);
            EmailService email = new EmailService(Optional.of(sender), config("smtp.gmail.com", "a@b.com", ""));

            assertThat(email.sendAccountApprovedEmail("t@example.com", "Ada", "ada", 50L, "GBP")).isTrue();

            ArgumentCaptor<SimpleMailMessage> message = ArgumentCaptor.forClass(SimpleMailMessage.class);
            verify(sender).send(message.capture());
            assertThat(message.getValue().getTo()).containsExactly("t@example.com");
            assertThat(message.getValue().getSubject()).contains("Approved");
            assertThat(message.getValue().getText())
                    .contains("Hello Ada")
                    .contains("approved and created")
                    .contains("#50 (GBP)")
                    .contains("Username: ada")
                    .contains("sign in");
        }

        @Test
        @DisplayName("an approval notice still reads well without an account number or currency")
        void approvalWithoutAccountDetails() {
            JavaMailSender sender = mock(JavaMailSender.class);
            EmailService email = new EmailService(Optional.of(sender), config("smtp.gmail.com", "a@b.com", ""));

            email.sendAccountApprovedEmail("t@example.com", "Ada", null, null, null);

            ArgumentCaptor<SimpleMailMessage> message = ArgumentCaptor.forClass(SimpleMailMessage.class);
            verify(sender).send(message.capture());
            assertThat(message.getValue().getText()).doesNotContain("Trading account").doesNotContain("Username");
        }

        @Test
        @DisplayName("sends the reinstatement notice")
        void sendsReinstatement() {
            JavaMailSender sender = mock(JavaMailSender.class);
            EmailService email = new EmailService(Optional.of(sender), config("smtp.gmail.com", "a@b.com", ""));

            assertThat(email.sendAccountReinstatedEmail("t@example.com", "Ada", "cleared")).isTrue();
            ArgumentCaptor<SimpleMailMessage> message = ArgumentCaptor.forClass(SimpleMailMessage.class);
            verify(sender).send(message.capture());
            assertThat(message.getValue().getSubject()).contains("Reinstated");
        }

        @Test
        @DisplayName("reports false, not an exception, when the mail server refuses")
        void reportsFailure() {
            JavaMailSender sender = mock(JavaMailSender.class);
            doThrow(new MailSendException("535 auth failed")).when(sender).send(any(SimpleMailMessage.class));
            EmailService email = new EmailService(Optional.of(sender), config("smtp.gmail.com", "a@b.com", ""));

            assertThat(email.sendAccountSuspensionEmail("t@example.com", "Ada", "r")).isFalse();
            assertThat(email.sendAccountReinstatedEmail("t@example.com", "Ada", "r")).isFalse();
            assertThat(email.sendAccountApprovedEmail("t@example.com", "Ada", "ada", 1L, "USD")).isFalse();
        }

        @Test
        @DisplayName("does not try to send when SMTP is not configured")
        void skipsWhenUnconfigured() {
            JavaMailSender sender = mock(JavaMailSender.class);
            EmailService email = new EmailService(Optional.of(sender), config("", "", ""));

            assertThat(email.sendAccountSuspensionEmail("t@example.com", "Ada", "r")).isFalse();
            verify(sender, never()).send(any(SimpleMailMessage.class));
        }

        @Test
        @DisplayName("does not try to send when there is no mail sender")
        void skipsWithoutSender() {
            EmailService email = new EmailService(Optional.empty(), config("smtp.gmail.com", "a@b.com", ""));
            assertThat(email.sendAccountReinstatedEmail("t@example.com", "Ada", "r")).isFalse();
        }
    }

    @Nested
    @DisplayName("AdminNotifications")
    class Notifications {

        private AdminUserDto user(String email, String firstName) {
            return new AdminUserDto(1L, "uuid", firstName, "Lovelace", "ada", email, "client", "+44", "1",
                    "UK", "BALANCED", "SUSPENDED", 10L, "SUSPENDED", "GBP", null,
                    Instant.now(), null, Instant.now(), null, null);
        }

        @Test
        @DisplayName("reports a delivered suspension email on the user it returns")
        void reportsDelivered() {
            EmailService email = mock(EmailService.class);
            org.mockito.Mockito.when(email.sendAccountSuspensionEmail("ada@example.com", "Ada", "r")).thenReturn(true);

            AdminUserDto result = new AdminNotifications(email).suspended(user("ada@example.com", "Ada"), "r");

            assertThat(result.emailDelivered()).isTrue();
        }

        @Test
        @DisplayName("tells an approved applicant their account, and reports delivery")
        void approvedSendsAccountDetails() {
            EmailService email = mock(EmailService.class);
            org.mockito.Mockito.when(email.sendAccountApprovedEmail("ada@example.com", "Ada", "ada", 10L, "GBP"))
                    .thenReturn(true);

            AdminUserDto result = new AdminNotifications(email).approved(user("ada@example.com", "Ada"));

            assertThat(result.emailDelivered()).isTrue();
        }

        @Test
        @DisplayName("does not try to send an approval to a user with no address")
        void approvedWithoutAddress() {
            EmailService email = mock(EmailService.class);
            AdminUserDto result = new AdminNotifications(email).approved(user(" ", "Ada"));
            assertThat(result.emailDelivered()).isFalse();
            verify(email, never()).sendAccountApprovedEmail(any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("greets by username when there is no first name")
        void greetsByUsername() {
            EmailService email = mock(EmailService.class);
            new AdminNotifications(email).reinstated(user("ada@example.com", " "), "r");
            verify(email).sendAccountReinstatedEmail("ada@example.com", "ada", "r");
        }

        @Test
        @DisplayName("reports no email for a user with no address, without trying to send")
        void noAddress() {
            EmailService email = mock(EmailService.class);
            AdminUserDto result = new AdminNotifications(email).suspended(user("", "Ada"), "r");
            assertThat(result.emailDelivered()).isFalse();
            verify(email, never()).sendAccountSuspensionEmail(any(), any(), any());
        }
    }

    @Nested
    @DisplayName("MailConfig")
    class Sender {

        @Test
        @DisplayName("builds a STARTTLS sender on 587 with the cleaned credentials")
        void startTls() {
            JavaMailSenderImpl sender = (JavaMailSenderImpl) new MailConfig()
                    .javaMailSender("\"smtp.gmail.com\"", "587", "\"me@gmail.com\"", "abcd efgh ijkl mnop");

            assertThat(sender.getHost()).isEqualTo("smtp.gmail.com");
            assertThat(sender.getPort()).isEqualTo(587);
            assertThat(sender.getUsername()).isEqualTo("me@gmail.com");
            assertThat(sender.getPassword()).isEqualTo("abcdefghijklmnop");
            assertThat(sender.getJavaMailProperties().getProperty("mail.smtp.starttls.enable")).isEqualTo("true");
            assertThat(sender.getJavaMailProperties().getProperty("mail.smtp.ssl.enable")).isEqualTo("false");
            assertThat(sender.getJavaMailProperties().getProperty("mail.smtp.auth")).isEqualTo("true");
        }

        @Test
        @DisplayName("uses implicit TLS on 465")
        void implicitTls() {
            JavaMailSenderImpl sender = (JavaMailSenderImpl) new MailConfig()
                    .javaMailSender("smtp.example.com", "465", "u", "p");
            assertThat(sender.getJavaMailProperties().getProperty("mail.smtp.ssl.enable")).isEqualTo("true");
            assertThat(sender.getJavaMailProperties().getProperty("mail.smtp.starttls.enable")).isEqualTo("false");
        }

        @Test
        @DisplayName("does not authenticate without a username")
        void noAuth() {
            JavaMailSenderImpl sender = (JavaMailSenderImpl) new MailConfig()
                    .javaMailSender("localhost", "25", "", "");
            assertThat(sender.getUsername()).isNull();
            assertThat(sender.getJavaMailProperties().getProperty("mail.smtp.auth")).isEqualTo("false");
        }
    }
}

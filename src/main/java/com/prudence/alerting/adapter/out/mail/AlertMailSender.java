package com.prudence.alerting.adapter.out.mail;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

import java.util.List;

/// Sends an alert by SMTP. SMTP is configured through Spring Boot's `spring.mail.*`
/// (SPRING_MAIL_HOST, _PORT, _USERNAME, _PASSWORD). Without it — dev only; production refuses
/// to start without SMTP — the alert is written to the log instead, so dev stays offline.
@Component
public class AlertMailSender {

    private static final Logger log = LoggerFactory.getLogger(AlertMailSender.class);

    private final JavaMailSender mail;
    private final String from;

    public AlertMailSender(ObjectProvider<JavaMailSender> mail, @Value("${prudence.alerts.from:prudence@localhost}") String from) {
        this.mail = mail.getIfAvailable();
        this.from = from;
    }

    public void send(List<String> to, String subject, String body) {
        if (mail == null) {
            log.warn("ALERT (no SMTP configured, not emailed) to={} subject={}\n{}", to, subject, body);
            return;
        }
        if (to.isEmpty()) {
            throw new IllegalStateException("alert has no recipients: set prudence.alerts.*-recipients");
        }
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(from);
        message.setTo(to.toArray(String[]::new));
        message.setSubject(subject);
        message.setText(body);
        mail.send(message);
    }
}

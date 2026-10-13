package com.prudence.alerting.adapter.out.mail;

import com.prudence.alerting.adapter.out.persistence.AlertJpaEntity;
import com.prudence.alerting.adapter.out.persistence.AlertJpaRepository;
import com.prudence.evidence.delivery.Delivery;
import com.prudence.evidence.delivery.DeliveryFailure;
import com.prudence.evidence.delivery.DeliveryTarget;
import jakarta.mail.SendFailedException;
import org.springframework.data.domain.Limit;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.MailParseException;
import org.springframework.mail.MailPreparationException;
import org.springframework.mail.MailSendException;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

/// Stored alerts → email, through the same worker, lease and retry policy as the evidence.
@Component
public class AlertDeliveryTarget implements DeliveryTarget<AlertJpaEntity> {

    private final AlertJpaRepository alerts;
    private final AlertMailSender mail;

    public AlertDeliveryTarget(AlertJpaRepository alerts, AlertMailSender mail) {
        this.alerts = alerts;
        this.mail = mail;
    }

    @Override public String name() { return "alerts"; }
    @Override public List<AlertJpaEntity> due(String now, int limit) { return alerts.due(now, Limit.of(limit)); }
    @Override public String id(AlertJpaEntity e) { return e.getId(); }
    @Override public Delivery delivery(AlertJpaEntity e) { return e.getDelivery(); }
    @Override public int claim(String id, String expected, String leaseUntil) { return alerts.claim(id, expected, leaseUntil); }
    @Override public Optional<AlertJpaEntity> find(String id) { return alerts.findById(id); }
    @Override public void save(AlertJpaEntity e) { alerts.save(e); }

    @Override
    public Consumer<AlertJpaEntity> send(AlertJpaEntity e) {
        mail.send(e.recipientList(), e.getSubject(), e.getBody());
        return record -> {};
    }

    /// The alert channel must not alert about itself (it would loop); its failures are logged.
    @Override
    public boolean reportsFailures() {
        return false;
    }

    /// SMTP onto the delivery contract: bad credentials or no recipients → CONFIGURATION;
    /// a malformed or rejected address → PERMANENT; anything else (server down, timeout) → RETRYABLE.
    @Override
    public DeliveryFailure classify(Exception error) {
        if (error instanceof MailAuthenticationException || error instanceof IllegalStateException) {
            return DeliveryFailure.CONFIGURATION;
        }
        if (error instanceof MailParseException || error instanceof MailPreparationException) {
            return DeliveryFailure.PERMANENT;
        }
        if (error instanceof MailSendException send) {
            for (Exception cause : send.getMessageExceptions()) {
                if (cause instanceof SendFailedException failed
                        && failed.getInvalidAddresses() != null && failed.getInvalidAddresses().length > 0) {
                    return DeliveryFailure.PERMANENT;
                }
            }
        }
        return DeliveryFailure.RETRYABLE;
    }
}

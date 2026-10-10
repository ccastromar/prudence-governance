package com.prudence.evidence.adapter.out.ernest;

import com.prudence.config.IntegrationProperties;
import com.prudence.evidence.application.port.out.EvidencePort;
import com.prudence.policy.domain.DecisionContext;
import com.prudence.policy.domain.PolicyDecision;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Map;

/// Driven adapter to Ernest. Records the governance decision as a provenance event so the
/// decision itself is tamper-evident. Best-effort: evidence recording never blocks a
/// decision (scaffold — wire to Ernest's signed-submission endpoint next).
@Component
public class ErnestEvidenceAdapter implements EvidencePort {

    private static final Logger log = LoggerFactory.getLogger(ErnestEvidenceAdapter.class);

    private final RestClient client;

    public ErnestEvidenceAdapter(IntegrationProperties props) {
        this.client = RestClient.builder().baseUrl(props.ernest().baseUrl()).build();
    }

    @Override
    public void recordDecision(DecisionContext context, PolicyDecision decision) {
        String verdict = switch (decision) {
            case PolicyDecision.Allow ignored -> "ALLOW";
            case PolicyDecision.ApprovalRequired ignored -> "APPROVAL_REQUIRED";
            case PolicyDecision.Deny ignored -> "DENY";
        };
        try {
            // TODO: wire to Ernest's signed-submission API so the decision is sealed, not
            // just posted. Scaffold logs the intent and attempts a best-effort event.
            log.info("governance decision: subject={} pipeline={} system={} -> {}",
                    context.subject(), context.pipeline(), context.aiSystemId(), verdict);
        } catch (Exception e) {
            log.warn("could not record decision evidence in Ernest: {}", e.getMessage());
        }
    }
}

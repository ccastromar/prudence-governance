package com.prudence.governance.application.service;

import com.prudence.governance.adapter.out.persistence.*;
import com.prudence.governance.domain.GovernanceCaseStatus;
import com.prudence.governance.domain.GovernanceCaseType;
import com.prudence.registry.application.port.in.AiSystemQueries;
import com.prudence.registry.domain.AiSystemId;
import com.prudence.registry.domain.AiSystemNotFoundException;
import com.prudence.shared.exception.DomainException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/// Coordinates a compact, auditable governance dossier. It stores decisions and verifiable
/// references, while source documents remain in DMS, GRC, Git or ticketing systems.
@Service
public class GovernanceCaseService {
    private final GovernanceCaseRepository cases;
    private final GovernanceEvidenceRepository evidence;
    private final GovernanceCaseEventRepository events;
    private final AiSystemQueries systems;
    public GovernanceCaseService(GovernanceCaseRepository cases, GovernanceEvidenceRepository evidence,
                                 GovernanceCaseEventRepository events, AiSystemQueries systems) {
        this.cases=cases; this.evidence=evidence; this.events=events; this.systems=systems;
    }
    @Transactional public GovernanceCaseJpaEntity open(String systemId, GovernanceCaseType type, String title, String summary, String owner, String actor) {
        systems.findById(new AiSystemId(systemId)).orElseThrow(() -> new AiSystemNotFoundException(new AiSystemId(systemId)));
        required(type, "type"); required(title, "title"); required(summary, "summary"); required(owner, "owner");
        String now=Instant.now().toString();
        GovernanceCaseJpaEntity c=cases.save(new GovernanceCaseJpaEntity(UUID.randomUUID().toString(), systemId, type, title.strip(), summary.strip(), owner.strip(), actor, now));
        event(c.getId(), "OPENED", actor, "Case opened: " + c.getType()); return c;
    }
    @Transactional(readOnly=true) public List<GovernanceCaseJpaEntity> list(){return cases.findAllByOrderByOpenedAtDesc();}
    @Transactional(readOnly=true) public GovernanceCaseJpaEntity get(String id){return cases.findById(id).orElseThrow(() -> new DomainException("governance case not found: " + id));}
    @Transactional(readOnly=true) public List<GovernanceEvidenceJpaEntity> evidence(String id){get(id); return evidence.findByCaseIdOrderByAddedAtAsc(id);}
    @Transactional(readOnly=true) public List<GovernanceCaseEventJpaEntity> timeline(String id){get(id); return events.findByCaseIdOrderByAtAsc(id);}
    @Transactional public GovernanceCaseJpaEntity startReview(String id, String actor) { GovernanceCaseJpaEntity c=get(id); c.startReview(Instant.now().toString()); event(id,"REVIEW_STARTED",actor,"Review started."); return cases.save(c); }
    @Transactional public GovernanceEvidenceJpaEntity addEvidence(String id, String label, String uri, String sha256, String version, String owner, String actor) {
        get(id); required(label,"label"); required(uri,"uri"); required(sha256,"sha256"); required(version,"version"); required(owner,"owner");
        String digest=sha256.startsWith("sha256:") ? sha256.substring(7) : sha256;
        if (!digest.matches("[a-fA-F0-9]{64}")) throw new DomainException("evidence sha256 must be a SHA-256 hex digest");
        String now=Instant.now().toString(); GovernanceEvidenceJpaEntity e=evidence.save(new GovernanceEvidenceJpaEntity(UUID.randomUUID().toString(),id,label.strip(),uri.strip(),sha256.strip(),version.strip(),owner.strip(),actor,now));
        event(id,"EVIDENCE_REFERENCED",actor,label.strip()+" · "+version.strip()+" · "+uri.strip()); return e;
    }
    @Transactional public GovernanceCaseJpaEntity close(String id, String decision, String actor) {
        GovernanceCaseJpaEntity c=get(id); required(decision,"closing decision");
        if (evidence.countByCaseId(id)==0) throw new DomainException("a governance case needs at least one evidence reference before closure");
        c.close(actor,decision.strip(),Instant.now().toString()); event(id,"CLOSED",actor,decision.strip()); return cases.save(c);
    }
    private void event(String id,String type,String actor,String details){events.save(new GovernanceCaseEventJpaEntity(UUID.randomUUID().toString(),id,type,actor,Instant.now().toString(),details));}
    private static void required(Object v,String name){if(v==null || (v instanceof String s && s.isBlank()))throw new DomainException(name+" is required");}
}

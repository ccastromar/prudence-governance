package com.prudence.governance.adapter.in.web;

import com.prudence.governance.adapter.out.persistence.*;
import com.prudence.governance.application.service.GovernanceCaseService;
import com.prudence.governance.domain.GovernanceCaseType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import java.security.Principal;
import java.util.List;

@RestController
@RequestMapping("/api/governance/cases")
public class GovernanceCaseController {
    private final GovernanceCaseService service;
    public GovernanceCaseController(GovernanceCaseService service){this.service=service;}
    @GetMapping public List<CaseResponse> list(){return service.list().stream().map(this::response).toList();}
    @GetMapping("/{id}") public CaseResponse get(@PathVariable String id){return response(service.get(id));}
    @PostMapping @ResponseStatus(HttpStatus.CREATED) public CaseResponse open(@Valid @RequestBody OpenCaseRequest r, Principal p){return response(service.open(r.aiSystemId(),r.type(),r.title(),r.summary(),r.owner(),actor(p)));}
    @PostMapping("/{id}/review") public CaseResponse review(@PathVariable String id, Principal p){return response(service.startReview(id,actor(p)));}
    @PostMapping("/{id}/evidence") public EvidenceResponse evidence(@PathVariable String id,@Valid @RequestBody EvidenceRequest r,Principal p){return evidence(service.addEvidence(id,r.label(),r.uri(),r.sha256(),r.version(),r.owner(),actor(p)));}
    @PostMapping("/{id}/close") public CaseResponse close(@PathVariable String id,@Valid @RequestBody CloseCaseRequest r,Principal p){return response(service.close(id,r.decision(),actor(p)));}
    private CaseResponse response(GovernanceCaseJpaEntity c){return new CaseResponse(c.getId(),c.getAiSystemId(),c.getType(),c.getStatus(),c.getTitle(),c.getSummary(),c.getOwner(),c.getOpenedBy(),c.getOpenedAt(),c.getReviewStartedAt(),c.getClosedAt(),c.getClosedBy(),c.getClosingDecision(),service.evidence(c.getId()).stream().map(this::evidence).toList(),service.timeline(c.getId()).stream().map(e->new EventResponse(e.getId(),e.getType(),e.getActor(),e.getAt(),e.getDetails())).toList());}
    private EvidenceResponse evidence(GovernanceEvidenceJpaEntity e){return new EvidenceResponse(e.getId(),e.getLabel(),e.getUri(),e.getSha256(),e.getVersion(),e.getOwner(),e.getAddedBy(),e.getAddedAt());}
    private static String actor(Principal p){return p==null?"dev-reviewer":p.getName();}
    public record OpenCaseRequest(@NotBlank String aiSystemId,@NotNull GovernanceCaseType type,@NotBlank String title,@NotBlank String summary,@NotBlank String owner){}
    public record EvidenceRequest(@NotBlank String label,@NotBlank String uri,@NotBlank String sha256,@NotBlank String version,@NotBlank String owner){}
    public record CloseCaseRequest(@NotBlank String decision){}
    public record CaseResponse(String id,String aiSystemId,GovernanceCaseType type,com.prudence.governance.domain.GovernanceCaseStatus status,String title,String summary,String owner,String openedBy,String openedAt,String reviewStartedAt,String closedAt,String closedBy,String closingDecision,List<EvidenceResponse> evidence,List<EventResponse> timeline){}
    public record EvidenceResponse(String id,String label,String uri,String sha256,String version,String owner,String addedBy,String addedAt){}
    public record EventResponse(String id,String type,String actor,String at,String details){}
}

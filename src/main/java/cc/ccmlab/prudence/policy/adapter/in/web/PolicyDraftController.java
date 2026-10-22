package cc.ccmlab.prudence.policy.adapter.in.web;

import cc.ccmlab.prudence.policy.adapter.out.audit.PolicyAudit;
import cc.ccmlab.prudence.policy.adapter.out.persistence.*;
import cc.ccmlab.prudence.policy.application.bundle.PolicyBundleBuilder;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

/** Draft storage only. Drafts are never consulted by the PDP or delivered to OPA. */
@RestController
@RequestMapping("/api/policy-admin")
public class PolicyDraftController {
  private final PolicyDefinitionRepository definitions;
  private final PolicyRevisionRepository revisions;
  private final PolicyDraftEventRepository events;
  private final ObjectMapper json;
  private final String principalMode;
  private final PolicyBundleBuilder bundleBuilder;
  private final PolicyAudit audit;

  public PolicyDraftController(
      PolicyDefinitionRepository definitions,
      PolicyRevisionRepository revisions,
      PolicyDraftEventRepository events,
      ObjectMapper json,
      PolicyBundleBuilder bundleBuilder,
      PolicyAudit audit,
      @Value("${prudence.identity.policy-actor-mode:${prudence.identity.principal-mode:legacy}}")
          String principalMode) {
    this.definitions = definitions;
    this.revisions = revisions;
    this.events = events;
    this.json = json;
    this.bundleBuilder = bundleBuilder;
    this.audit = audit;
    this.principalMode = PolicyActors.validatedMode(principalMode);
  }

  // Deliberately bounded: this is only a stored draft, not yet an executable policy schema.
  public record Rule(
      @NotBlank @Size(max = 255) String role,
      @NotNull @Pattern(regexp = "pipeline\\.run") String action,
      @NotBlank @Size(max = 255) String pipeline,
      @NotNull @Pattern(regexp = "ALLOW|DENY") String effect) {}

  public record CreateDraft(
      @NotBlank @Size(max = 200) String name,
      @Size(max = 4000) String description,
      @NotEmpty @Size(max = 100) List<@Valid @NotNull Rule> rules) {}

  public record UpdateDraft(
      @NotNull @PositiveOrZero Long version,
      @NotEmpty @Size(max = 100) List<@Valid @NotNull Rule> rules) {}

  public record TransitionRequest(@NotNull @PositiveOrZero Long version) {}

  public record Event(String id, String type, String actor, OffsetDateTime occurredAt) {}

  /** An inspection artifact, not a publication or an activation signal. */
  @GetMapping("/drafts/{revisionId}/bundle-preview")
  @Transactional(readOnly = true)
  public ResponseEntity<byte[]> bundlePreview(
      @PathVariable String revisionId, Authentication auth) {
    actor(auth);
    var revision = revision(revisionId);
    if (!Set.of("APPROVED", "ACTIVE", "SUPERSEDED").contains(revision.getStatus()))
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "only approved revisions have a bundle preview");
    var bundle =
        bundleBuilder.build(
            revision.getId(), view(definition(revision.getPolicyId()), revision).rules());
    return ResponseEntity.ok()
        .contentType(MediaType.parseMediaType("application/gzip"))
        .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=policy-preview.tar.gz")
        .header("X-Policy-Version", bundle.version())
        .header("X-Policy-Digest", bundle.digest())
        .body(bundle.archive());
  }

  @GetMapping("/drafts/{revisionId}/events")
  @Transactional(readOnly = true)
  public List<Event> events(@PathVariable String revisionId, Authentication auth) {
    actor(auth);
    revision(revisionId);
    return events.findByRevisionIdOrderByOccurredAtAsc(revisionId).stream()
        .map(
            event ->
                new Event(
                    event.getId(), event.getEventType(), event.getActor(), event.getOccurredAt()))
        .toList();
  }

  @PostMapping("/drafts/{revisionId}/reopen")
  @Transactional
  public Draft reopen(
      @PathVariable String revisionId,
      @Valid @RequestBody TransitionRequest request,
      Authentication auth) {
    String actor = actor(auth);
    var revision = revision(revisionId);
    try {
      revision.reopen(request.version());
    } catch (IllegalStateException ex) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, ex.getMessage());
    }
    revisions.saveAndFlush(revision);
    saveEvent(
        new PolicyDraftEventEntity(
            revision.getPolicyId(),
            revisionId,
            "REOPENED",
            actor,
            OffsetDateTime.now(),
            revision.getRulesJson()));
    return view(definition(revision.getPolicyId()), revision);
  }

  @PostMapping("/drafts/{revisionId}/fork")
  @ResponseStatus(HttpStatus.CREATED)
  @Transactional
  public Draft fork(
      @PathVariable String revisionId,
      @Valid @RequestBody TransitionRequest request,
      Authentication auth) {
    String actor = actor(auth);
    var source = revision(revisionId);
    if (source.getVersion() != request.version())
      throw new ResponseStatusException(HttpStatus.CONFLICT, "stale revision version");
    if (!Set.of("APPROVED", "ACTIVE", "SUPERSEDED", "FAILED").contains(source.getStatus()))
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "only approved or published revisions can be forked");
    var all = revisions.findByPolicyIdOrderByRevisionNumberDesc(source.getPolicyId());
    if (all.stream().anyMatch(r -> Set.of("DRAFT", "VALIDATED").contains(r.getStatus())))
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "a draft already exists for this policy");
    int number = all.getFirst().getRevisionNumber() + 1;
    var now = OffsetDateTime.now();
    var draft =
        revisions.saveAndFlush(
            new PolicyRevisionEntity(
                UUID.randomUUID().toString(),
                source.getPolicyId(),
                number,
                source.getRulesJson(),
                actor,
                now));
    saveEvent(
        new PolicyDraftEventEntity(
            source.getPolicyId(), draft.getId(), "FORKED", actor, now, source.getRulesJson()));
    return view(definition(source.getPolicyId()), draft);
  }

  public record SimulationRequest(
      @NotEmpty @Size(max = 100) List<@NotBlank @Size(max = 255) String> roles,
      @NotNull @Pattern(regexp = "pipeline\\.run") String action,
      @NotBlank @Size(max = 255) String pipeline) {}

  public record SimulationResult(
      String revisionId, long version, String decision, String reason, boolean authoritative) {}

  public record Draft(
      String policyId,
      String name,
      String description,
      String revisionId,
      int revision,
      String status,
      long version,
      List<Rule> rules,
      String createdBy,
      OffsetDateTime createdAt,
      String approvedBy,
      OffsetDateTime approvedAt) {}

  @PostMapping("/drafts")
  @ResponseStatus(HttpStatus.CREATED)
  @Transactional
  public Draft create(@Valid @RequestBody CreateDraft request, Authentication auth) {
    String actor = actor(auth);
    String policyId = UUID.randomUUID().toString();
    String revisionId = UUID.randomUUID().toString();
    OffsetDateTime now = OffsetDateTime.now();
    String rules = json.writeValueAsString(request.rules());
    var definition =
        definitions.save(
            new PolicyDefinitionEntity(
                policyId,
                request.name().strip(),
                request.description() == null ? "" : request.description(),
                actor,
                now));
    var revision =
        revisions.save(new PolicyRevisionEntity(revisionId, policyId, rules, actor, now));
    saveEvent(new PolicyDraftEventEntity(policyId, revisionId, "CREATED", actor, now, rules));
    return view(definition, revision);
  }

  @GetMapping("/drafts/{revisionId}")
  @Transactional(readOnly = true)
  public Draft get(@PathVariable String revisionId, Authentication auth) {
    actor(auth);
    var revision = revision(revisionId);
    return view(definition(revision.getPolicyId()), revision);
  }

  /** A preview of one draft only: not a PDP verdict and not permission to execute. */
  @PostMapping("/drafts/{revisionId}/simulate")
  @Transactional(readOnly = true)
  public SimulationResult simulate(
      @PathVariable String revisionId,
      @Valid @RequestBody SimulationRequest request,
      Authentication auth) {
    actor(auth);
    var revision = revision(revisionId);
    if (!"DRAFT".equals(revision.getStatus()))
      throw new ResponseStatusException(HttpStatus.CONFLICT, "only drafts can be simulated");
    List<Rule> rules = view(definition(revision.getPolicyId()), revision).rules();
    var matches =
        rules.stream()
            .filter(
                rule ->
                    request.roles().contains(rule.role())
                        && request.action().equals(rule.action())
                        && request.pipeline().equals(rule.pipeline()))
            .toList();
    boolean denied = matches.stream().anyMatch(rule -> "DENY".equals(rule.effect()));
    boolean allowed = matches.stream().anyMatch(rule -> "ALLOW".equals(rule.effect()));
    String decision = !denied && allowed ? "ALLOW" : "DENY";
    String reason =
        denied ? "matching deny rule" : allowed ? "matching allow rule" : "no matching rule";
    return new SimulationResult(revisionId, revision.getVersion(), decision, reason, false);
  }

  @PostMapping("/drafts/{revisionId}/validate")
  @Transactional
  public Draft validate(
      @PathVariable String revisionId,
      @Valid @RequestBody TransitionRequest request,
      Authentication auth) {
    String actor = actor(auth);
    var revision = revision(revisionId);
    var definition = definition(revision.getPolicyId());
    List<Rule> rules = view(definition, revision).rules();
    Set<List<String>> seen = new HashSet<>();
    for (Rule rule : rules) {
      if (!seen.add(List.of(rule.role(), rule.action(), rule.pipeline())))
        throw new ResponseStatusException(
            HttpStatus.UNPROCESSABLE_ENTITY, "duplicate or conflicting rule");
    }
    try {
      revision.validateDraft(request.version());
    } catch (IllegalStateException ex) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, ex.getMessage());
    }
    revisions.saveAndFlush(revision);
    saveEvent(
        new PolicyDraftEventEntity(
            revision.getPolicyId(),
            revisionId,
            "VALIDATED",
            actor,
            OffsetDateTime.now(),
            revision.getRulesJson()));
    return view(definition, revision);
  }

  @PostMapping("/drafts/{revisionId}/approve")
  @Transactional
  public Draft approve(
      @PathVariable String revisionId,
      @Valid @RequestBody TransitionRequest request,
      Authentication auth) {
    String actor = actor(auth);
    if (!"issuer-subject".equals(principalMode))
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "approval requires stable issuer-subject identities");
    var revision = revision(revisionId);
    if (events.existsByRevisionIdAndActor(revisionId, actor))
      throw new ResponseStatusException(
          HttpStatus.FORBIDDEN, "policy contributor cannot approve this revision");
    if (events.findByRevisionIdOrderByOccurredAtAsc(revisionId).stream()
        .anyMatch(e -> !PolicyActors.isStable(e.getActor())))
      throw new ResponseStatusException(
          HttpStatus.CONFLICT,
          "revision has contributors recorded before stable identities; create a new revision");
    OffsetDateTime now = OffsetDateTime.now();
    try {
      revision.approve(request.version(), actor, now);
    } catch (IllegalStateException ex) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, ex.getMessage());
    }
    revisions.saveAndFlush(revision);
    saveEvent(
        new PolicyDraftEventEntity(
            revision.getPolicyId(), revisionId, "APPROVED", actor, now, revision.getRulesJson()));
    return view(definition(revision.getPolicyId()), revision);
  }

  @GetMapping("/definitions")
  @Transactional(readOnly = true)
  public List<PolicyDefinitionEntity> list(Authentication auth) {
    actor(auth);
    return definitions.findAll();
  }

  @GetMapping("/definitions/{policyId}/revisions")
  @Transactional(readOnly = true)
  public List<Draft> revisions(@PathVariable String policyId, Authentication auth) {
    actor(auth);
    var definition = definition(policyId);
    return revisions.findByPolicyIdOrderByRevisionNumberDesc(policyId).stream()
        .map(revision -> view(definition, revision))
        .toList();
  }

  @ExceptionHandler({
    DataIntegrityViolationException.class,
    OptimisticLockingFailureException.class
  })
  @ResponseStatus(HttpStatus.CONFLICT)
  public void conflict() {}

  @PutMapping("/drafts/{revisionId}")
  @Transactional
  public Draft update(
      @PathVariable String revisionId,
      @Valid @RequestBody UpdateDraft request,
      Authentication auth) {
    String actor = actor(auth);
    var revision = revision(revisionId);
    String rules = json.writeValueAsString(request.rules());
    try {
      revision.updateDraft(rules, request.version());
    } catch (IllegalStateException ex) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, ex.getMessage());
    }
    revisions.saveAndFlush(revision);
    saveEvent(
        new PolicyDraftEventEntity(
            revision.getPolicyId(), revisionId, "UPDATED", actor, OffsetDateTime.now(), rules));
    return view(definition(revision.getPolicyId()), revision);
  }

  private PolicyRevisionEntity revision(String id) {
    return revisions
        .findById(id)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
  }

  private PolicyDefinitionEntity definition(String id) {
    return definitions
        .findById(id)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
  }

  private Draft view(PolicyDefinitionEntity definition, PolicyRevisionEntity revision) {
    List<Rule> rules =
        json.readValue(
            revision.getRulesJson(),
            json.getTypeFactory().constructCollectionType(List.class, Rule.class));
    return new Draft(
        definition.getId(),
        definition.getName(),
        definition.getDescription(),
        revision.getId(),
        revision.getRevisionNumber(),
        revision.getStatus(),
        revision.getVersion(),
        rules,
        revision.getCreatedBy(),
        revision.getCreatedAt(),
        revision.getApprovedBy(),
        revision.getApprovedAt());
  }

  private String actor(Authentication auth) {
    return PolicyActors.of(auth, principalMode);
  }

  private void saveEvent(PolicyDraftEventEntity event) {
    events.save(event);
    audit.record(
        event.getPolicyId(),
        event.getEventType(),
        event.getActor(),
        json.writeValueAsString(
            java.util.Map.of(
                "eventId",
                event.getId(),
                "revisionId",
                event.getRevisionId(),
                "rules",
                json.readTree(event.getRulesJson()))));
  }
}

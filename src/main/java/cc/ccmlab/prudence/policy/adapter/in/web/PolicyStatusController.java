package cc.ccmlab.prudence.policy.adapter.in.web;

import cc.ccmlab.prudence.policy.application.bundle.PolicyPublicationService;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * What the console needs to explain the policy area truthfully: whether managed publication is on,
 * which identity mode approvals use, the caller's policy roles and the publication the channel
 * currently requires. A required publication is a request, never proof that OPA activated it.
 */
@RestController
public class PolicyStatusController {
  private final PolicyPublicationService publications;
  private final boolean managed;
  private final String actorMode;

  public PolicyStatusController(
      PolicyPublicationService publications,
      @Value("${prudence.managed.enabled:false}") boolean managed,
      @Value("${prudence.identity.policy-actor-mode:${prudence.identity.principal-mode:legacy}}")
          String actorMode) {
    this.publications = publications;
    this.managed = managed;
    this.actorMode = actorMode;
  }

  public record Status(
      boolean managed,
      String actorMode,
      boolean approvalAvailable,
      List<String> roles,
      String requiredPublicationId,
      String requiredDigest) {}

  @GetMapping("/api/policy-admin/status")
  public Status status(Authentication auth) {
    var roles =
        auth.getAuthorities().stream()
            .map(a -> a.getAuthority())
            .filter(a -> a.startsWith("ROLE_"))
            .map(a -> a.substring(5))
            .sorted()
            .toList();
    var current = managed ? publications.current() : null;
    return new Status(
        managed,
        actorMode,
        "issuer-subject".equals(actorMode),
        roles,
        current == null ? null : current.id(),
        current == null ? null : current.digest());
  }
}

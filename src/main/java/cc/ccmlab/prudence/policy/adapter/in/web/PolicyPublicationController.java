package cc.ccmlab.prudence.policy.adapter.in.web;

import cc.ccmlab.prudence.policy.adapter.out.opa.OpaPolicyAdapter;
import cc.ccmlab.prudence.policy.application.bundle.PolicyPublicationService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@ConditionalOnProperty(name = "prudence.managed.enabled", havingValue = "true")
public class PolicyPublicationController {
  private final PolicyPublicationService publications;
  private final OpaPolicyAdapter opa;
  private final byte[] bundleKey;
  private final boolean requireTls;
  private final String actorMode;

  public PolicyPublicationController(
      PolicyPublicationService publications,
      OpaPolicyAdapter opa,
      @Value("${prudence.managed.bundle-key:}") String key,
      @Value("${prudence.identity.policy-actor-mode:${prudence.identity.principal-mode:legacy}}")
          String principalMode,
      Environment environment) {
    if (key.length() < 32)
      throw new IllegalStateException("managed bundle key must contain at least 32 characters");
    if (!"issuer-subject".equals(principalMode))
      throw new IllegalStateException("managed policies require issuer-subject identity mode");
    this.actorMode = principalMode;
    this.publications = publications;
    this.opa = opa;
    this.bundleKey = key.getBytes(StandardCharsets.UTF_8);
    this.requireTls = !environment.acceptsProfiles(Profiles.of("dev", "test"));
  }

  public record Publish(@NotBlank String revisionId, @NotNull @PositiveOrZero Long version) {}

  public record Rollback(@NotBlank String publicationId) {}

  @PostMapping("/api/policy-admin/publications")
  public PolicyPublicationService.Publication publish(
      @Valid @RequestBody Publish request, Authentication auth) {
    try {
      return publications.publish(request.revisionId(), request.version(), actor(auth));
    } catch (IllegalArgumentException e) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
    } catch (IllegalStateException e) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
    }
  }

  @PostMapping("/api/policy-admin/publications/rollback")
  public PolicyPublicationService.Publication rollback(
      @Valid @RequestBody Rollback request, Authentication auth) {
    try {
      return publications.rollback(request.publicationId(), actor(auth));
    } catch (IllegalArgumentException e) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
    } catch (IllegalStateException e) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
    }
  }

  @GetMapping("/api/policy-admin/publications")
  public List<PolicyPublicationService.Publication> history(Authentication auth) {
    actor(auth);
    return publications.history();
  }

  @PostMapping("/api/policy-admin/publications/probe")
  public OpaPolicyAdapter.Probe probe(Authentication auth) {
    actor(auth);
    return opa.probe();
  }

  @GetMapping("/api/policy-admin/publications/observations")
  public List<PolicyPublicationService.Observation> observations(Authentication auth) {
    actor(auth);
    return publications.observations();
  }

  @GetMapping("/internal/policy-bundles/current")
  public ResponseEntity<byte[]> current(
      @RequestHeader(value = "X-OPA-Bundle-Key", required = false) String key,
      HttpServletRequest request) {
    if (requireTls && !request.isSecure()) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
    byte[] provided = key == null ? new byte[0] : key.getBytes(StandardCharsets.UTF_8);
    if (!MessageDigest.isEqual(bundleKey, provided))
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
    var bundle = publications.current();
    if (bundle == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    return ResponseEntity.ok()
        .contentType(MediaType.parseMediaType("application/gzip"))
        .header(HttpHeaders.ETAG, "\"" + bundle.id() + "\"")
        .header("X-Policy-Digest", bundle.digest())
        .body(bundle.bundle());
  }

  private String actor(Authentication auth) {
    return PolicyActors.of(auth, actorMode);
  }
}

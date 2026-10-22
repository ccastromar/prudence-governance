package cc.ccmlab.prudence.policy.adapter.in.web;

import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.server.ResponseStatusException;

/**
 * Identity recorded on policy drafts, approvals and publications. Policy actors are derived here
 * from the access token so that stable issuer+subject identities can be used for the policy control
 * plane without changing the principal names the registry, approvals and OPA grants already rely
 * on.
 */
final class PolicyActors {
  static final List<String> MODES = List.of("legacy", "issuer-subject");

  private PolicyActors() {}

  static String validatedMode(String mode) {
    if (!MODES.contains(mode))
      throw new IllegalStateException("unsupported policy actor mode: " + mode);
    return mode;
  }

  static String of(Authentication auth, String mode) {
    if (auth == null
        || !auth.isAuthenticated()
        || auth instanceof AnonymousAuthenticationToken
        || auth.getName() == null
        || auth.getName().isBlank()) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
    String actor = auth.getName();
    if ("issuer-subject".equals(mode)) {
      if (!(auth instanceof JwtAuthenticationToken jwtAuth))
        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
      String issuer = jwtAuth.getToken().getClaimAsString("iss");
      String subject = jwtAuth.getToken().getSubject();
      if (issuer == null || issuer.isBlank() || subject == null || subject.isBlank())
        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
      actor = issuer.length() + ":" + issuer + subject;
    }
    if (actor.length() > 512) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
    return actor;
  }

  /** True for the length-prefixed issuer+subject form, e.g. {@code 12:https://idpsub}. */
  static boolean isStable(String actor) {
    if (actor == null) return false;
    int colon = actor.indexOf(':');
    if (colon < 1 || colon > 4) return false;
    try {
      int issuerLength = Integer.parseInt(actor.substring(0, colon));
      return issuerLength > 0 && actor.length() > colon + 1 + issuerLength;
    } catch (NumberFormatException e) {
      return false;
    }
  }
}

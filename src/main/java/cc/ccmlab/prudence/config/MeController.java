package cc.ccmlab.prudence.config;

import java.util.List;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Tells the console who the caller is and which Prudence roles it holds, after the deployment's
 * role mapping. The browser must not read roles from the token: their names and claim are the
 * identity provider's, not Prudence's.
 */
@RestController
class MeController {

  record Me(String name, List<String> roles) {}

  @GetMapping("/api/me")
  Me me(Authentication auth) {
    if (auth == null) return new Me("", List.of());
    List<String> roles =
        auth.getAuthorities().stream()
            .map(a -> a.getAuthority())
            .filter(a -> a.startsWith("ROLE_"))
            .map(a -> a.substring(5))
            .sorted()
            .toList();
    return new Me(auth.getName(), roles);
  }
}

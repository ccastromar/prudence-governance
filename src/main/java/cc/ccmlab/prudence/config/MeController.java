package cc.ccmlab.prudence.config;

import cc.ccmlab.prudence.governance.Fn;
import java.util.List;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Tells the console who the caller is, which Prudence roles it holds after the deployment's role
 * mapping, and which governance functions (contract v1) its token grants in Prudence. The browser
 * must not read roles from the token: their names and claim are the identity provider's, not
 * Prudence's.
 */
@RestController
class MeController {

  record Me(String name, List<String> roles, List<String> functions) {}

  @GetMapping("/api/me")
  Me me(Authentication auth) {
    // No authentication means the open development chain, which allows every request.
    if (auth == null) return new Me("", List.of(), Fn.personFunctions());
    List<String> roles =
        auth.getAuthorities().stream()
            .map(a -> a.getAuthority())
            .filter(a -> a.startsWith("ROLE_"))
            .map(a -> a.substring(5))
            .sorted()
            .toList();
    List<String> functions =
        auth.getAuthorities().stream()
            .map(a -> a.getAuthority())
            .filter(a -> a.startsWith("FN_"))
            .map(a -> a.substring(3))
            .sorted()
            .toList();
    return new Me(auth.getName(), roles, functions);
  }
}

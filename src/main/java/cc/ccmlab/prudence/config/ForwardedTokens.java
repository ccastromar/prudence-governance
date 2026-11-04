package cc.ccmlab.prudence.config;

import cc.ccmlab.prudence.governance.GovernanceModel;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.stereotype.Component;

/// Tokens a caller forwards for someone else (`contract/identity/` sections 3.2 and 4): the
/// agent's own token, or the person's. Each is validated exactly like a token Prudence accepts
/// on its own API (signature, issuer, audience, expiry: the same decoder), and its holder is
/// named and given functions exactly as a caller would be (the same converter), so an agent's
/// id is the same whether it asks Prudence itself or a gateway asks for it.
@Component
public class ForwardedTokens {

  /// A token that failed validation; the message says why, for the recorded decision.
  public static final class InvalidToken extends Exception {
    public InvalidToken(String why) {
      super(why);
    }
  }

  /// Who a valid token names, that holder's governance functions across the suite and its
  /// Prudence roles (as the same token would give it on Prudence's API).
  public record Holder(String id, List<String> functions, List<String> roles) {}

  private final ObjectProvider<JwtDecoder> decoders;
  private final String rolesClaim;
  private final String principalMode;
  private final String roleMapping;
  private final ObjectProvider<GovernanceModel> governance;

  public ForwardedTokens(
      ObjectProvider<JwtDecoder> decoders,
      @Value("${prudence.identity.roles-claim:roles}") String rolesClaim,
      @Value("${prudence.identity.principal-mode:legacy}") String principalMode,
      @Value("${prudence.identity.role-mapping:}") String roleMapping,
      ObjectProvider<GovernanceModel> governance) {
    this.decoders = decoders;
    this.rolesClaim = rolesClaim;
    this.principalMode = principalMode;
    this.roleMapping = roleMapping;
    this.governance = governance;
  }

  /// False without an issuer (the open development profile): nothing could validate a token.
  public boolean available() {
    return decoders.getIfAvailable() != null;
  }

  /// The holder's functions in every app of the suite, not only Prudence's: the person an agent
  /// acts for uses AI through that agent wherever it runs, and `ai.use:<domain>` is an AOS
  /// function (contract/governance). Without a model, none.
  private List<String> functionsOf(java.util.Map<String, Object> claims) {
    GovernanceModel model = governance.getIfAvailable();
    if (model == null) return List.of();
    java.util.Set<String> all = new java.util.TreeSet<>();
    for (String app : GovernanceModel.APPS) all.addAll(model.resolve(claims, app));
    return List.copyOf(all);
  }

  public Holder verify(String token) throws InvalidToken {
    JwtDecoder decoder = decoders.getIfAvailable();
    if (decoder == null) throw new InvalidToken("no token issuer is configured");
    try {
      var jwt = decoder.decode(token.strip());
      var holder =
          SecurityConfig.rolesConverter(
                  rolesClaim, principalMode, roleMapping, governance.getIfAvailable())
              .convert(jwt);
      if (holder == null) throw new InvalidToken("token names no one");
      List<String> roles =
          holder.getAuthorities().stream()
              .map(a -> a.getAuthority())
              .filter(a -> a.startsWith("ROLE_"))
              .map(a -> a.substring(5))
              .sorted()
              .toList();
      return new Holder(holder.getName(), functionsOf(jwt.getClaims()), roles);
    } catch (InvalidToken invalid) {
      throw invalid;
    } catch (RuntimeException e) {
      // JwtException (signature, issuer, audience, expiry) or a token that names no one.
      throw new InvalidToken(e.getMessage() == null ? "invalid token" : e.getMessage());
    }
  }
}

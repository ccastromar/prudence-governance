package cc.ccmlab.prudence.policy.adapter.out.opa;

import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Verifies the structured response of the managed evaluator against a separately established
 * required publication identity. This type does not verify the bundle payload or establish trust in
 * OPA; those are responsibilities of authenticated distribution and activation.
 *
 * <p>The managed adapter uses this result while holding the publication channel lock. Legacy
 * deployments continue to use the existing boolean decision endpoint.
 */
public record ManagedOpaResult(Outcome outcome, String version, String digest) {
  public enum Outcome {
    ALLOW,
    DENY,
    ERROR
  }

  private static final Pattern DIGEST = Pattern.compile("sha256:[0-9a-f]{64}");

  public static ManagedOpaResult verify(
      Object response, String requiredVersion, String requiredDigest) {
    if (requiredVersion == null
        || requiredVersion.isBlank()
        || requiredDigest == null
        || !DIGEST.matcher(requiredDigest).matches()) return error();
    if (!(response instanceof Map<?, ?> envelope)
        || !(envelope.get("result") instanceof Map<?, ?> result)) return error();
    Object version = result.get("version");
    Object digest = result.get("digest");
    Object decision = result.get("decision");
    if (!Objects.equals(requiredVersion, version) || !Objects.equals(requiredDigest, digest))
      return error();
    if ("ALLOW".equals(decision))
      return new ManagedOpaResult(Outcome.ALLOW, requiredVersion, requiredDigest);
    if ("DENY".equals(decision))
      return new ManagedOpaResult(Outcome.DENY, requiredVersion, requiredDigest);
    return error();
  }

  private static ManagedOpaResult error() {
    return new ManagedOpaResult(Outcome.ERROR, null, null);
  }
}

package cc.ccmlab.prudence.config;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Private bridges to the governed demo runners (integrations/demo-runner, protocol v1), one per
 * runner image. The browser never receives a runner's bearer token, nor the runners' M2M and Ernest
 * credentials.
 */
@ConfigurationProperties(prefix = "prudence.demo-runner")
public record DemoRunnerProperties(List<Runner> runners) {
  public record Runner(String baseUrl, String token) {
    public boolean configured() {
      return baseUrl != null && !baseUrl.isBlank() && token != null && !token.isBlank();
    }
  }

  /// The runners with both a URL and a token, in configuration order.
  public List<Runner> configured() {
    return runners == null ? List.of() : runners.stream().filter(Runner::configured).toList();
  }
}

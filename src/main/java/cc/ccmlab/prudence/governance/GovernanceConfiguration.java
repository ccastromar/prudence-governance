package cc.ccmlab.prudence.governance;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;

/**
 * Loads the organization's governance model, or the default one that reproduces the roles Prudence
 * used before governance functions. An invalid model stops the start: it would leave nobody, or the
 * wrong people, able to act.
 */
@Configuration
class GovernanceConfiguration {

  private static final Logger log = LoggerFactory.getLogger(GovernanceConfiguration.class);
  static final String DEFAULT_MODEL = "governance/default-model.yaml";

  /** Prudence records a first and a final sign-off, so a tier can ask for two approvals at most. */
  static final int MAX_APPROVALS = 2;

  @Bean
  GovernanceModel governanceModel(@Value("${prudence.governance.model:}") String path)
      throws IOException {
    GovernanceModel model;
    if (path == null || path.isBlank()) {
      log.info("governance model: the default (contract v1 default-model.yaml)");
      model = GovernanceModel.load(new ClassPathResource(DEFAULT_MODEL).getInputStream());
    } else {
      log.info("governance model: {}", path);
      model = GovernanceModel.load(Files.newInputStream(Path.of(path)));
    }
    return supported(model);
  }

  /** Refuses a valid model that asks Prudence for more than it can enforce. */
  static GovernanceModel supported(GovernanceModel model) {
    for (String tier : new String[] {"HIGH", "LIMITED", "MINIMAL"}) {
      if (model.approvals(tier).count() > MAX_APPROVALS) {
        throw new GovernanceModelException(
            "rules.approvals",
            tier + " asks for more than " + MAX_APPROVALS + " approvals; Prudence enforces two");
      }
    }
    return model;
  }
}

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

  @Bean
  GovernanceModel governanceModel(@Value("${prudence.governance.model:}") String path)
      throws IOException {
    if (path == null || path.isBlank()) {
      log.info("governance model: the default (contract v1 default-model.yaml)");
      return GovernanceModel.load(new ClassPathResource(DEFAULT_MODEL).getInputStream());
    }
    log.info("governance model: {}", path);
    return GovernanceModel.load(Files.newInputStream(Path.of(path)));
  }
}

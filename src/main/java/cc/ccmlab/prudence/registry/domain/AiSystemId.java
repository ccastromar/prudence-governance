package cc.ccmlab.prudence.registry.domain;

import java.util.UUID;

/// Identity of an AI system in the governance registry.
public record AiSystemId(String value) {
  public AiSystemId {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("AiSystemId must not be blank");
    }
  }

  public static AiSystemId newId() {
    return new AiSystemId(UUID.randomUUID().toString());
  }
}

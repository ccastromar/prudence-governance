package cc.ccmlab.prudence.config;

import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/// The AuthZEN front door's configuration (ADR-001).
///
/// `toolSystems` maps an MCP tool name to the id of the AI system in the registry that the tool
/// acts for, so an MCP gateway can ask about a tool call without knowing Prudence's ids:
/// `prudence.authzen.tool-systems[send_email]=<aiSystemId>`. A tool that is neither mapped
/// here nor sent with `resource.properties.aiSystemId` is refused (fail closed).
@ConfigurationProperties(prefix = "prudence.authzen")
public record AuthZenProperties(Map<String, String> toolSystems) {
  public AuthZenProperties {
    toolSystems = toolSystems == null ? Map.of() : Map.copyOf(toolSystems);
  }
}

package cc.ccmlab.prudence.registry.agents;

import java.security.Principal;
import java.util.List;
import org.springframework.web.bind.annotation.*;

/// The agents an AI system declares. `POST` replaces the list (system.declare).
@RestController
@RequestMapping("/api/registry/systems/{id}/agents")
public class SystemAgentController {
  private final SystemAgentService service;

  public SystemAgentController(SystemAgentService service) {
    this.service = service;
  }

  @GetMapping
  public SystemAgentService.Declaration list(@PathVariable String id) {
    return new SystemAgentService.Declaration(id, service.agentsOf(id), false);
  }

  @PostMapping
  public SystemAgentService.Declaration declare(
      @PathVariable String id, @RequestBody Body body, Principal principal) {
    return service.declare(
        id,
        body == null ? List.of() : body.agentIds(),
        principal == null ? null : principal.getName());
  }

  public record Body(List<String> agentIds) {}
}

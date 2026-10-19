package cc.ccmlab.prudence.posture.adapter.in.web;

import cc.ccmlab.prudence.posture.adapter.out.executions.ExecutionSourcesCheck;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/// Whether runtime execution reports and Ernest's execution evidence tell the same story.
@RestController
public class ExecutionSourcesController {

  private final ExecutionSourcesCheck check;

  public ExecutionSourcesController(ExecutionSourcesCheck check) {
    this.check = check;
  }

  @GetMapping("/api/posture/execution-sources")
  public ExecutionSourcesCheck.Result executionSources() {
    return check.check();
  }
}

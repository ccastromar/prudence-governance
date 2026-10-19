package cc.ccmlab.prudence.posture.adapter.in.web;

import cc.ccmlab.prudence.evidence.sink.EvidenceSinkInfo;
import cc.ccmlab.prudence.evidence.sink.EvidenceSinkService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/// The evidence store this installation delivers to, and what it can and cannot guarantee.
@RestController
public class EvidenceSinkController {

  private final EvidenceSinkService sink;

  public EvidenceSinkController(EvidenceSinkService sink) {
    this.sink = sink;
  }

  @GetMapping("/api/posture/evidence-sink")
  public EvidenceSinkInfo evidenceSink() {
    return sink.info();
  }
}

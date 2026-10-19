package cc.ccmlab.prudence.posture.application.port.in;

import cc.ccmlab.prudence.posture.domain.Posture;

/// Driving port: compute the estate's current compliance posture — the oversight step that
/// closes the control loop (Prudence reads the evidence back after AOS has run).
public interface PostureQuery {
  Posture compute();
}

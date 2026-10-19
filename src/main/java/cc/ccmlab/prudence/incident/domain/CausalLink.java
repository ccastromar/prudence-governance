package cc.ccmlab.prudence.incident.domain;

/// What is known about the link between the AI system and the harm. Art. 73 asks for a report
/// once a causal link is established *or reasonably likely*; the deadline runs from awareness
/// either way. A link that has been ruled out lifts the reporting obligation.
public enum CausalLink {
  SUSPECTED,
  ESTABLISHED,
  RULED_OUT
}

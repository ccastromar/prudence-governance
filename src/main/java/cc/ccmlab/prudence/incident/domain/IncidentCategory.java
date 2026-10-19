package cc.ccmlab.prudence.incident.domain;

/// Classification of an incident against the AI Act's definition of a *serious incident*
/// (Art. 3(49)): an incident or malfunctioning that directly or indirectly leads to one of the
/// harms below. Anything else is recorded, but carries no Art. 73 reporting obligation.
public enum IncidentCategory {
  /// Art. 3(49)(a), death — reporting deadline 10 days (Art. 73(4)).
  DEATH,
  /// Art. 3(49)(a), serious harm to a person's health — 15 days (Art. 73(2)).
  SERIOUS_HARM_TO_HEALTH,
  /// Art. 3(49)(b), serious and irreversible disruption of critical infrastructure — 2 days (Art.
  // 73(3)).
  CRITICAL_INFRASTRUCTURE_DISRUPTION,
  /// Art. 3(49)(c), infringement of Union-law obligations protecting fundamental rights — 15 days.
  FUNDAMENTAL_RIGHTS_INFRINGEMENT,
  /// Art. 3(49)(d), serious harm to property or the environment — 15 days.
  PROPERTY_OR_ENVIRONMENT_HARM,
  /// A malfunction or incident that does not meet the serious-incident definition.
  NOT_SERIOUS;

  public boolean isSerious() {
    return this != NOT_SERIOUS;
  }
}

package cc.ccmlab.prudence.governance;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * A validated Chestertown governance model (contract/governance/README.md): which governance
 * functions each IdP group or role grants, and the rules an organization sets within the legal
 * floor. {@link #resolve} turns a token's claims into the functions one app grants.
 */
public final class GovernanceModel {

  public static final String VERSION = "chestertown.governance/v1";
  public static final List<String> APPS = List.of("aos", "prudence", "ernest");

  private enum Kind {
    PERSON,
    MACHINE
  }

  private record Function(Kind kind, Set<String> apps) {}

  // contract/governance/README.md, section 2.
  private static final Map<String, Function> FUNCTIONS =
      Map.ofEntries(
          Map.entry("system.declare", new Function(Kind.PERSON, Set.of("prudence"))),
          Map.entry("system.approve", new Function(Kind.PERSON, Set.of("prudence"))),
          Map.entry("policy.author", new Function(Kind.PERSON, Set.of("prudence"))),
          Map.entry("policy.approve", new Function(Kind.PERSON, Set.of("prudence"))),
          Map.entry("oversight", new Function(Kind.PERSON, Set.of("aos", "prudence"))),
          Map.entry("incident.handle", new Function(Kind.PERSON, Set.of("prudence"))),
          Map.entry("ai.use", new Function(Kind.PERSON, Set.of("aos"))),
          Map.entry("evidence.write", new Function(Kind.PERSON, Set.of("ernest"))),
          Map.entry("audit", new Function(Kind.PERSON, Set.of("aos", "prudence", "ernest"))),
          Map.entry(
              "platform.admin", new Function(Kind.PERSON, Set.of("aos", "prudence", "ernest"))),
          Map.entry("runtime.decide", new Function(Kind.MACHINE, Set.of("prudence"))),
          Map.entry("runtime.report", new Function(Kind.MACHINE, Set.of("prudence"))));

  private static final List<String> TIERS = List.of("HIGH", "LIMITED", "MINIMAL");
  private static final List<String> ACCOUNTABILITIES =
      List.of("OWNER", "SUPERVISOR", "COMPLIANCE", "DPO", "SECURITY");
  private static final Pattern GRANT =
      Pattern.compile("^([a-z]+(?:\\.[a-z]+)?)(?::([a-z0-9][a-z0-9_-]*|\\*))?(?:@([a-z]+))?$");

  /** One parsed grant: `function[:domain][@app]`. */
  public record Grant(String function, String domain, String app) {
    String granted() {
      return domain == null ? function : function + ":" + domain;
    }
  }

  private final String peopleClaim;
  private final String machinesClaim;
  private final Map<String, List<Grant>> map;
  private final Map<String, Object> rules;

  private GovernanceModel(
      String peopleClaim,
      String machinesClaim,
      Map<String, List<Grant>> map,
      Map<String, Object> rules) {
    this.peopleClaim = peopleClaim;
    this.machinesClaim = machinesClaim;
    this.map = map;
    this.rules = rules;
  }

  /** Reads and validates a YAML (or JSON) model; throws {@link GovernanceModelException}. */
  public static GovernanceModel load(InputStream yaml) throws IOException {
    try (yaml) {
      return of(new Yaml(new SafeConstructor(new LoaderOptions())).load(yaml));
    }
  }

  /**
   * Validates a parsed model (README.md, section 4). Throws on the first rule it breaks, with the
   * contract's error code.
   */
  public static GovernanceModel of(Object model) {
    if (!(model instanceof Map<?, ?> root) || !VERSION.equals(root.get("version"))) {
      throw new GovernanceModelException("model.version", "expected " + VERSION);
    }
    String people = claimOf(root.get("identity"), "people");
    String machines = claimOf(root.get("identity"), "machines");

    if (!(root.get("map") instanceof Map<?, ?> rawMap)) {
      throw new GovernanceModelException("model.map", "map must be an object");
    }
    Map<String, List<Grant>> map = new LinkedHashMap<>();
    for (Map.Entry<?, ?> entry : rawMap.entrySet()) {
      if (!(entry.getKey() instanceof String key)
          || key.isEmpty()
          || !(entry.getValue() instanceof List<?> raw)
          || raw.isEmpty()) {
        throw new GovernanceModelException("model.map", "entry " + entry.getKey());
      }
      List<Grant> grants = new ArrayList<>();
      Set<Kind> kinds = new TreeSet<>();
      for (Object g : raw) {
        Grant grant = parseGrant(g);
        grants.add(grant);
        kinds.add(FUNCTIONS.get(grant.function()).kind());
      }
      if (kinds.size() > 1) throw new GovernanceModelException("grant.mixed_kinds", key);
      map.put(key, List.copyOf(grants));
    }

    Map<String, Object> rules = new LinkedHashMap<>();
    if (root.get("rules") instanceof Map<?, ?> rawRules) {
      rawRules.forEach((k, v) -> rules.put(String.valueOf(k), v));
    }
    validateApprovals(rules.get("approvals"));
    validateIncompatible(rules.get("incompatible"));
    validateAccountable(rules.get("accountable"));
    return new GovernanceModel(people, machines, Map.copyOf(map), Map.copyOf(rules));
  }

  /** The functions `app` grants to a token with these claims (README.md, section 5). */
  public List<String> resolve(Map<String, Object> claims, String app) {
    Set<String> granted = new TreeSet<>();
    collect(granted, claimValues(claims, peopleClaim), Kind.PERSON, app);
    collect(granted, claimValues(claims, machinesClaim), Kind.MACHINE, app);
    return List.copyOf(granted);
  }

  /** The organization's rules as written in the model; read by the rule checks (phase 4). */
  public Map<String, Object> rules() {
    return rules;
  }

  /** How many approvals a risk tier needs, and whether they must come from distinct people. */
  public record Approvals(int count, boolean distinct) {}

  /** The approvals `tier` needs. A tier the model leaves out needs one approval. */
  public Approvals approvals(String tier) {
    if (rules.get("approvals") instanceof Map<?, ?> all
        && all.get(tier) instanceof Map<?, ?> rule
        && rule.get("count") instanceof Number count) {
      return new Approvals(count.intValue(), Boolean.TRUE.equals(rule.get("distinct")));
    }
    return new Approvals(1, false);
  }

  /** Whether one identity may not exercise both functions on the same system, draft or task. */
  public boolean incompatible(String first, String second) {
    if (!(rules.get("incompatible") instanceof List<?> pairs)) return false;
    for (Object pair : pairs) {
      if (pair instanceof List<?> p
          && p.size() == 2
          && (p.equals(List.of(first, second)) || p.equals(List.of(second, first)))) {
        return true;
      }
    }
    return false;
  }

  /** The accountabilities a system of `tier` needs before it is submitted. */
  public List<String> accountable(String tier) {
    if (rules.get("accountable") instanceof Map<?, ?> all
        && all.get(tier) instanceof List<?> roles) {
      return roles.stream().map(String::valueOf).toList();
    }
    return List.of();
  }

  private void collect(Set<String> granted, List<String> values, Kind kind, String app) {
    for (String value : values) {
      for (Grant grant : map.getOrDefault(value, List.of())) {
        Function function = FUNCTIONS.get(grant.function());
        if (function.kind() != kind || !function.apps().contains(app)) continue;
        if (grant.app() != null && !grant.app().equals(app)) continue;
        granted.add(grant.granted());
      }
    }
  }

  static List<String> claimValues(Map<String, Object> claims, String name) {
    Object value = claims;
    for (String part : name.split("\\.", -1)) {
      if (!(value instanceof Map<?, ?> m) || !m.containsKey(part)) return List.of();
      value = m.get(part);
    }
    if (value instanceof String s) {
      return s.isBlank() ? List.of() : List.of(s.trim().split("\\s+"));
    }
    if (value instanceof Collection<?> list) {
      List<String> values = new ArrayList<>();
      for (Object v : list) if (v instanceof String s) values.add(s);
      return values;
    }
    return List.of();
  }

  static Grant parseGrant(Object raw) {
    if (!(raw instanceof String grant)) {
      throw new GovernanceModelException("grant.syntax", String.valueOf(raw));
    }
    Matcher m = GRANT.matcher(grant);
    if (!m.matches()) throw new GovernanceModelException("grant.syntax", grant);
    String function = m.group(1), domain = m.group(2), app = m.group(3);
    if (!FUNCTIONS.containsKey(function)) {
      throw new GovernanceModelException("grant.unknown_function", grant);
    }
    if (domain != null && !function.equals("ai.use")) {
      throw new GovernanceModelException("grant.domain_not_allowed", grant);
    }
    if (domain == null && function.equals("ai.use")) {
      throw new GovernanceModelException("grant.domain_required", grant);
    }
    if (app != null) {
      if (!APPS.contains(app)) throw new GovernanceModelException("grant.unknown_app", grant);
      if (!FUNCTIONS.get(function).apps().contains(app)) {
        throw new GovernanceModelException("grant.app_not_applicable", grant);
      }
    }
    return new Grant(function, domain, app);
  }

  private static String claimOf(Object identity, String kind) {
    if (identity instanceof Map<?, ?> id
        && id.get(kind) instanceof Map<?, ?> section
        && section.get("claim") instanceof String claim
        && !claim.isEmpty()) {
      return claim;
    }
    throw new GovernanceModelException("model.identity", "identity." + kind + ".claim");
  }

  private static void validateApprovals(Object raw) {
    Map<?, ?> approvals = raw instanceof Map<?, ?> m ? m : Map.of();
    if (raw != null && !(raw instanceof Map<?, ?>)) {
      throw new GovernanceModelException("rules.approvals", "approvals must be an object");
    }
    for (Map.Entry<?, ?> e : approvals.entrySet()) {
      if (!TIERS.contains(e.getKey())
          || !(e.getValue() instanceof Map<?, ?> rule)
          || !(rule.get("count") instanceof Integer count)
          || count < 1) {
        throw new GovernanceModelException("rules.approvals", String.valueOf(e.getKey()));
      }
    }
    if (!(approvals.get("HIGH") instanceof Map<?, ?> high)
        || !(high.get("count") instanceof Integer count)
        || count < 2
        || !Boolean.TRUE.equals(high.get("distinct"))) {
      throw new GovernanceModelException(
          "floor.approvals.HIGH", "HIGH needs count >= 2 and distinct: true");
    }
  }

  private static void validateIncompatible(Object raw) {
    if (raw == null) return;
    if (!(raw instanceof List<?> pairs)) {
      throw new GovernanceModelException("rules.incompatible", "incompatible must be a list");
    }
    for (Object p : pairs) {
      if (!(p instanceof List<?> pair)
          || pair.size() != 2
          || java.util.Objects.equals(pair.get(0), pair.get(1))
          || !pair.stream().allMatch(GovernanceModel::isPersonFunction)) {
        throw new GovernanceModelException("rules.incompatible", String.valueOf(p));
      }
    }
  }

  private static boolean isPersonFunction(Object f) {
    return f instanceof String name
        && FUNCTIONS.containsKey(name)
        && FUNCTIONS.get(name).kind() == Kind.PERSON;
  }

  private static void validateAccountable(Object raw) {
    if (raw != null && !(raw instanceof Map<?, ?>)) {
      throw new GovernanceModelException("rules.accountable", "accountable must be an object");
    }
    Map<?, ?> accountable = raw instanceof Map<?, ?> m ? m : Map.of();
    for (Map.Entry<?, ?> e : accountable.entrySet()) {
      if (!TIERS.contains(e.getKey())
          || !(e.getValue() instanceof List<?> roles)
          || !roles.stream().allMatch(ACCOUNTABILITIES::contains)) {
        throw new GovernanceModelException("rules.accountable", String.valueOf(e.getKey()));
      }
    }
    if (!(accountable.get("HIGH") instanceof List<?> high) || !high.contains("SUPERVISOR")) {
      throw new GovernanceModelException("floor.accountable.HIGH", "HIGH needs SUPERVISOR");
    }
  }
}

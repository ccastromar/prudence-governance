package cc.ccmlab.prudence.governance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Phase 4: the rules a governance model sets, as Prudence reads them. */
class GovernanceRulesTest {

  private static GovernanceModel model(String rules) {
    String yaml =
        "version: chestertown.governance/v1\n"
            + "rules:\n"
            + rules
            + "identity: {people: {claim: roles}, machines: {claim: scope}}\n"
            + "map: {}\n";
    try {
      return GovernanceModel.load(new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static final String FLOOR =
      "  approvals: {HIGH: {count: 2, distinct: true}}\n" + "  accountable: {HIGH: [SUPERVISOR]}\n";

  @Test
  void readsApprovalsAndDefaultsAMissingTierToOne() {
    GovernanceModel m =
        model(
            "  approvals: {HIGH: {count: 2, distinct: true}, LIMITED: {count: 2}}\n"
                + "  accountable: {HIGH: [SUPERVISOR]}\n");
    assertEquals(new GovernanceModel.Approvals(2, true), m.approvals("HIGH"));
    assertEquals(new GovernanceModel.Approvals(2, false), m.approvals("LIMITED"));
    assertEquals(new GovernanceModel.Approvals(1, false), m.approvals("MINIMAL"));
  }

  @Test
  void incompatiblePairsWorkInEitherOrder() {
    GovernanceModel m = model(FLOOR + "  incompatible: [[system.declare, system.approve]]\n");
    assertTrue(m.incompatible("system.declare", "system.approve"));
    assertTrue(m.incompatible("system.approve", "system.declare"));
    assertFalse(m.incompatible("policy.author", "policy.approve"));
  }

  @Test
  void readsAccountabilities() {
    GovernanceModel m =
        model(
            "  approvals: {HIGH: {count: 2, distinct: true}}\n"
                + "  accountable: {HIGH: [SUPERVISOR, DPO]}\n");
    assertEquals(List.of("SUPERVISOR", "DPO"), m.accountable("HIGH"));
    assertEquals(List.of(), m.accountable("LIMITED"));
  }

  @Test
  void prudenceRefusesMoreThanTwoApprovals() {
    GovernanceModel m =
        model(
            "  approvals: {HIGH: {count: 3, distinct: true}}\n"
                + "  accountable: {HIGH: [SUPERVISOR]}\n");
    GovernanceModelException e =
        assertThrows(GovernanceModelException.class, () -> GovernanceConfiguration.supported(m));
    assertEquals("rules.approvals", e.code());
  }
}

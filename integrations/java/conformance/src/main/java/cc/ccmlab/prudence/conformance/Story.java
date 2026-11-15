package cc.ccmlab.prudence.conformance;

import cc.ccmlab.prudence.governance.ErnestEvidence;
import cc.ccmlab.prudence.governance.GovernOptions;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * What every agent here shares: the run's settings from the environment (the conformance kit's
 * protocol, integrations/conformance-kit/README.md) and the one line it prints at the end.
 */
final class Story {
  static final String TOOL = env("TOOL", env("DEMO_TOOL", "send_customer_letter"));
  static final String DESCRIPTION =
      env("DEMO_TOOL_DESCRIPTION", "Send the standard claims letter to a customer.");
  static final String TASK = env("DEMO_TASK", "Send the letter to customer C-1");
  static final String RESULT = env("DEMO_RESULT", "sent to {reference}");
  static final String LLM_URL = env("LLM_URL", "http://127.0.0.1:8000/v1");
  static final String LLM_MODEL = env("LLM_MODEL", "conformance-scripted");
  static final String LLM_API_KEY = env("LLM_API_KEY", "none");

  /** Calls of the tool's body: the kit checks that it ran only on ALLOW. */
  static final List<String> RAN = new ArrayList<>();

  private Story() {}

  static String env(String name, String fallback) {
    String value = System.getenv(name);
    return value == null || value.isEmpty() ? fallback : value;
  }

  /** The AI system (empty: none), the tool's pipeline when PIPELINE is set, Ernest when set. */
  static GovernOptions governance() {
    GovernOptions.Builder options = GovernOptions.builder().aiSystemId(env("AI_SYSTEM_ID", ""));
    String pipeline = env("PIPELINE", "");
    if (!pipeline.isEmpty()) options.pipelines(Map.of(TOOL, pipeline));
    if (!env("ERNEST_URL", "").isEmpty()) options.evidence(new ErnestEvidence());
    return options.build();
  }

  static String runTool(String reference) {
    RAN.add(reference);
    return RESULT.replace("{reference}", reference);
  }

  static void finish(String answer) {
    String json =
        "{\"toolRan\": "
            + !RAN.isEmpty()
            + ", \"answer\": "
            + cc.ccmlab.prudence.conformance.Main.quote(answer == null ? "" : answer)
            + "}";
    System.out.println(json);
  }
}

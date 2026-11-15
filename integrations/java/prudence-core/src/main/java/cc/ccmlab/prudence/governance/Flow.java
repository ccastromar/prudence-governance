package cc.ccmlab.prudence.governance;

import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.regex.Pattern;

/**
 * One agent run, one flow id. Every decision taken inside {@link #run} carries the same {@code
 * correlationId} (Decision API v1), so in Prudence, and in the evidence each decision leaves in
 * Ernest, the calls of one agent run read as one flow.
 *
 * <pre>{@code
 * String answer = Flow.run(() -> chatClient.prompt(task).call().content());     // a fresh id
 * String answer = Flow.run("ticket-4711", () -> assistant.chat(task));          // your own id
 * }</pre>
 *
 * <p>The id is held per thread: a framework that runs tools on other threads (LangChain4j's {@code
 * executeToolsConcurrently()}) does not see it. Give those a fixed id on the gate instead ({@code
 * PrudenceGate.builder().correlationId(…)}).
 */
public final class Flow {
  private static final Pattern CORRELATION = Pattern.compile("^[A-Za-z0-9._:-]{1,128}$");
  private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();

  private Flow() {}

  /**
   * The id itself; Prudence accepts only a plain token of at most 128 characters ({@code
   * [A-Za-z0-9._:-]}) and refuses the whole decision otherwise.
   *
   * @throws IllegalArgumentException when it is not one
   */
  public static String check(String correlationId) {
    if (correlationId == null || !CORRELATION.matcher(correlationId).matches()) {
      throw new IllegalArgumentException("correlation id must match [A-Za-z0-9._:-]{1,128}");
    }
    return correlationId;
  }

  /** The flow id of the enclosing {@code run}, or null. */
  public static String current() {
    return CURRENT.get();
  }

  /** Run {@code body} as one flow under a fresh id. */
  public static <T> T run(Callable<T> body) throws Exception {
    return run("run-" + UUID.randomUUID(), body);
  }

  /** Run {@code body} as one flow under {@code correlationId}. */
  public static <T> T run(String correlationId, Callable<T> body) throws Exception {
    String previous = CURRENT.get();
    CURRENT.set(check(correlationId));
    try {
      return body.call();
    } finally {
      if (previous == null) CURRENT.remove();
      else CURRENT.set(previous);
    }
  }
}

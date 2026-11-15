package cc.ccmlab.prudence.conformance;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * One agent per Java framework, each with one tool governed by its Prudence adapter:
 *
 * <pre>
 * java -jar target/prudence-java-conformance.jar spring-ai
 * java -jar target/prudence-java-conformance.jar langchain4j
 * </pre>
 */
public final class Main {
  private static final ObjectMapper JSON = new ObjectMapper();

  private Main() {}

  public static void main(String[] args) throws Exception {
    String framework = args.length > 0 ? args[0] : "";
    switch (framework) {
      case "spring-ai" -> SpringAiAgent.run();
      case "langchain4j" -> LangChain4jAgent.run();
      default -> {
        System.err.println("usage: prudence-java-conformance.jar spring-ai|langchain4j");
        System.exit(2);
      }
    }
  }

  static String quote(String text) {
    try {
      return JSON.writeValueAsString(text);
    } catch (Exception e) {
      return "\"\"";
    }
  }
}

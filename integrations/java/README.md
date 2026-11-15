# Prudence for Java agent frameworks

The same governance as the Python and TypeScript adapters, for agents written in Java: one core and
a thin adapter per framework, all following the [adapter contract](../../contract/adapter/README.md).
A tool call is asked as `tool:<name>` with `tools/call` (the grant AI and MCP gateways use too),
only an ALLOW runs it, the model reads the refusal otherwise, and the configuration comes from the
same variables (`PRUDENCE_PDP_URL`, `PRUDENCE_PDP_TOKEN_URL`, …).

```
prudence-core/   cc.ccmlab.prudence:prudence-core — PrudenceGate, M2MAuth, ErnestEvidence, Flow,
                   ToolTarget, and the ToolGovernor both adapters share (Jackson 2 only)
spring-ai/       cc.ccmlab.prudence:prudence-spring-ai — governed ToolCallbacks (Spring AI 2.0)
langchain4j/     cc.ccmlab.prudence:prudence-langchain4j — governed ToolExecutors and ToolProviders
                   (LangChain4j 1.x)
conformance/     one agent per framework, each with one governed tool: what the conformance kit runs
```

Java 17 or later. Nothing is published yet: `mvn install` here puts the three libraries in your
local repository.

## Spring AI

```java
ToolCallback[] tools = PrudenceTools.govern(GovernOptions.of("68bf42c1-…"), ToolCallbacks.from(new ClaimsTools()));
String answer = chatClient.prompt("Send the letter to customer C-1").toolCallbacks(tools).call().content();

// an MCP client's tools, re-read and governed on every request:
ToolCallbackProvider mcp = PrudenceTools.govern(options, mcpToolCallbackProvider);
```

Each `ToolCallback` is wrapped: `call()` asks Prudence and, on anything but ALLOW, returns the
refusal as the tool's result. Spring AI gives a tool no call id, so the report uses a fresh one.

## LangChain4j

```java
Assistant assistant = AiServices.builder(Assistant.class)
    .chatModel(model)
    .tools(PrudenceTools.govern(GovernOptions.of("68bf42c1-…"), new ClaimsTools()))   // @Tool methods
    .toolProvider(PrudenceTools.govern(options, mcpToolProvider))                    // or a provider
    .build();
```

Each `ToolExecutor` is wrapped, on every path the AI service can take (`execute`,
`executeWithContext`, `executeAsync`). Reports carry the call's id.

## Common to both

- **Pipeline per tool:** `tool:<tool name>` with `tools/call` (`scripts/provision-runtime.py --tool
  <name>`), or `GovernOptions.builder().pipelines(Map.of(name, "claims_letter"))`, asked with `run`.
  A tool nobody granted is denied.
- **Only what goes through `PrudenceTools.govern` is governed.** List read-only tools in
  `ungoverned(Set.of(…))`.
- **AI system:** `aiSystemId(…)` for the agent, or `aiSystems(Map.of(tool, id))`; a tool with none is
  refused without asking Prudence.
- **Reported when allowed,** and with `evidence(new ErnestEvidence())` sealed in Ernest (only a
  SHA-256 of the arguments, the same hash the Python and TypeScript cores compute). Both run in the
  background and are drained for up to ten seconds when the JVM exits.
- **Credentials:** `PRUDENCE_PDP_URL`, `PRUDENCE_PDP_TOKEN_URL`, `PRUDENCE_PDP_CLIENT_ID`,
  `PRUDENCE_PDP_CLIENT_SECRET`, or `gate(PrudenceGate.builder()…build())`.
- **One flow per run:** `Flow.run(() -> assistant.chat(task))` gives every decision inside one
  `correlationId`. It is held per thread: with LangChain4j's `executeToolsConcurrently()`, set a
  fixed one on the gate (`PrudenceGate.builder().correlationId(…)`) instead.
- Not yet in the Java core: qualified electronic seals (the Python core's `QtspSealer`).

## Prove it

```bash
cd integrations/java && mvn -q install -DskipTests && cd ../..
integrations/conformance-kit/kit.py -- java -jar integrations/java/conformance/target/prudence-java-conformance.jar spring-ai
integrations/conformance-kit/kit.py -- java -jar integrations/java/conformance/target/prudence-java-conformance.jar langchain4j
```

Format before committing: `mvn spotless:apply` (google-java-format, as Prudence itself).

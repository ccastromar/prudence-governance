package cc.ccmlab.prudence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import cc.ccmlab.prudence.policy.application.port.in.DecidePolicyUseCase;
import cc.ccmlab.prudence.policy.domain.DecisionContext;
import cc.ccmlab.prudence.policy.domain.PolicyDecision;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.yaml.snakeyaml.Yaml;
import tools.jackson.databind.ObjectMapper;

/// Prudence's side of the Decision API contract (contract/decision): for every outcome of the PDP,
/// the status and body the gates' vectors expect, every body valid against
/// docs/api/decision-api-v1.yaml and without undeclared fields; the requests refused before
/// deciding; and the statuses of a sequence of execution reports.
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.MOCK,
    properties = {
      "spring.datasource.url=jdbc:h2:mem:prudence-decision-contract;DB_CLOSE_DELAY=-1",
      "spring.datasource.username=sa",
      "spring.datasource.password=",
      "spring.jpa.hibernate.ddl-auto=create-drop",
      "prudence.ernest.base-url=http://127.0.0.1:1",
      "prudence.opa.base-url=http://127.0.0.1:1"
    })
@ActiveProfiles("test")
class DecisionApiContractTest {

  private static final ObjectMapper JSON = new ObjectMapper();
  private static final Path CONTRACT = Path.of("contract/decision/vectors");
  private static final Map<String, Object> SPEC = spec();

  @Autowired private WebApplicationContext context;
  @Autowired private cc.ccmlab.prudence.config.CorrelationIdFilter correlationIdFilter;
  @MockitoBean private DecidePolicyUseCase pdp;
  private MockMvc mvc;

  @BeforeEach
  void setUp() {
    mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(correlationIdFilter).build();
  }

  @Test
  @SuppressWarnings("unchecked")
  void everyOutcomeHasTheStatusAndBodyGatesExpect() throws Exception {
    Map<String, Object> provider = vectors("provider.json");
    String request = JSON.writeValueAsString(provider.get("request"));
    for (var vector : (List<Map<String, Object>>) provider.get("decide")) {
      String outcome = (String) vector.get("outcome");
      reset(pdp);
      when(pdp.decideWithDetails(any()))
          .thenReturn(new DecidePolicyUseCase.Result(outcome(vector), "lab", "sha256:" + outcome));

      MvcResult result = decide(request);

      assertEquals(vector.get("status"), result.getResponse().getStatus(), outcome);
      Map<String, Object> body =
          JSON.readValue(result.getResponse().getContentAsString(), Map.class);
      assertEquals(vector.get("decision"), body.get("decision"), outcome);
      assertEquals(vector.get("reason"), body.get("reason"), outcome + ": reason");
      assertEquals(vector.get("approver"), body.get("approver"), outcome + ": approver");
      assertTrue(
          body.get("decisionId") instanceof String id && !id.isBlank(), outcome + ": decisionId");
      assertEquals(List.of(), errors(body, schema("DecisionResponse"), "$"), outcome + ": schema");
    }
  }

  @Test
  @SuppressWarnings("unchecked")
  void invalidRequestsAreRefusedBeforeDeciding() throws Exception {
    Map<String, Object> provider = vectors("provider.json");
    for (var vector : (List<Map<String, Object>>) provider.get("invalid")) {
      reset(pdp);
      var request = patched((Map<String, Object>) provider.get("request"), vector);
      MvcResult result = decide(JSON.writeValueAsString(request));
      assertEquals(400, result.getResponse().getStatus(), (String) vector.get("name"));
      verify(pdp, never()).decideWithDetails(any());
    }
  }

  @Test
  @SuppressWarnings("unchecked")
  void executionReportsAnswerAsRuntimesExpect() throws Exception {
    Map<String, Object> report = (Map<String, Object>) vectors("provider.json").get("report");
    var base = (Map<String, Object>) report.get("report");
    for (var step : (List<Map<String, Object>>) report.get("steps")) {
      String name = (String) step.get("name");
      var body = patched(base, step);
      MvcResult result =
          mvc.perform(
                  post("/api/executions")
                      .contentType(MediaType.APPLICATION_JSON)
                      .content(JSON.writeValueAsString(body)))
              .andReturn();
      int status = result.getResponse().getStatus();
      assertEquals(step.get("status"), status, name);
      if (status == 200 || status == 201) {
        Map<String, Object> record =
            JSON.readValue(result.getResponse().getContentAsString(), Map.class);
        assertEquals(List.of(), errors(record, schema("ExecutionReportRecord"), "$"), name);
      }
    }
  }

  @Test
  @SuppressWarnings("unchecked")
  void theCorrelationIdIsRecordedWithTheDecision() throws Exception {
    Map<String, Object> request =
        new LinkedHashMap<>((Map<String, Object>) vectors("provider.json").get("request"));
    var captured = org.mockito.ArgumentCaptor.forClass(DecisionContext.class);

    reset(pdp);
    when(pdp.decideWithDetails(any()))
        .thenReturn(new DecidePolicyUseCase.Result(new PolicyDecision.Allow(), "lab", null));
    request.put("correlationId", "task-4f1c2a5e");
    assertEquals(200, decide(JSON.writeValueAsString(request)).getResponse().getStatus());
    verify(pdp).decideWithDetails(captured.capture());
    assertEquals("task-4f1c2a5e", captured.getValue().correlationId());

    // Without the field, the X-Correlation-Id header.
    reset(pdp);
    when(pdp.decideWithDetails(any()))
        .thenReturn(new DecidePolicyUseCase.Result(new PolicyDecision.Allow(), "lab", null));
    request.remove("correlationId");
    mvc.perform(
            post("/api/policy/decisions")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Correlation-Id", "task-from-header")
                .content(JSON.writeValueAsString(request)))
        .andReturn();
    verify(pdp).decideWithDetails(captured.capture());
    assertEquals("task-from-header", captured.getValue().correlationId());

    // Not a plain token: refused before deciding.
    reset(pdp);
    request.put("correlationId", "has spaces and <tags>");
    assertEquals(400, decide(JSON.writeValueAsString(request)).getResponse().getStatus());
    verify(pdp, never()).decideWithDetails(any());
  }

  private MvcResult decide(String body) throws Exception {
    return mvc.perform(
            post("/api/policy/decisions").contentType(MediaType.APPLICATION_JSON).content(body))
        .andReturn();
  }

  private static PolicyDecision outcome(Map<String, Object> vector) {
    String reason = (String) vector.get("reason");
    return switch ((String) vector.get("outcome")) {
      case "allow" -> new PolicyDecision.Allow();
      case "approval_required" ->
          new PolicyDecision.ApprovalRequired((String) vector.get("approver"), reason);
      case "deny" -> new PolicyDecision.Deny(reason);
      case "error" -> new PolicyDecision.Error(reason);
      default -> throw new IllegalArgumentException("unknown outcome " + vector.get("outcome"));
    };
  }

  /// RFC 7386 merge patch, one level deep: null removes the field.
  @SuppressWarnings("unchecked")
  private static Map<String, Object> patched(Map<String, Object> base, Map<String, Object> step) {
    var result = new LinkedHashMap<>(base);
    ((Map<String, Object>) step.get("patch"))
        .forEach(
            (key, value) -> {
              if (value == null) result.remove(key);
              else result.put(key, value);
            });
    return result;
  }

  /// The subset of JSON Schema the spec uses ($ref, allOf, type, enum, required, properties).
  /// A field the schema does not declare is an error: add it to the spec first.
  @SuppressWarnings("unchecked")
  private static List<String> errors(Object value, Map<String, Object> schema, String path) {
    if (schema.containsKey("$ref")) return errors(value, ref((String) schema.get("$ref")), path);
    if (schema.containsKey("allOf")) {
      var required = new ArrayList<Object>();
      var properties = new HashMap<String, Object>();
      for (var part : (List<Map<String, Object>>) schema.get("allOf")) {
        var resolved = part.containsKey("$ref") ? ref((String) part.get("$ref")) : part;
        required.addAll((List<Object>) resolved.getOrDefault("required", List.of()));
        properties.putAll((Map<String, Object>) resolved.getOrDefault("properties", Map.of()));
      }
      return errors(
          value, Map.of("type", "object", "required", required, "properties", properties), path);
    }
    var found = new ArrayList<String>();
    Object type = schema.get("type");
    if (type != null) {
      List<Object> types = type instanceof List<?> l ? (List<Object>) l : List.of(type);
      if (types.stream().noneMatch(t -> is(value, (String) t))) {
        return List.of(path + ": " + value + " is not " + types);
      }
    }
    if (schema.containsKey("enum") && !((List<?>) schema.get("enum")).contains(value)) {
      found.add(path + ": " + value + " not in " + schema.get("enum"));
    }
    if (value instanceof Map<?, ?> map) {
      var properties = (Map<String, Object>) schema.getOrDefault("properties", Map.of());
      for (var key : (List<Object>) schema.getOrDefault("required", List.of())) {
        if (!map.containsKey(key)) found.add(path + ": " + key + " is required");
      }
      map.forEach(
          (key, item) -> {
            if (!properties.containsKey(key))
              found.add(path + ": " + key + " is not in the schema");
            else
              found.addAll(
                  errors(item, (Map<String, Object>) properties.get(key), path + "." + key));
          });
    }
    return found;
  }

  private static boolean is(Object value, String type) {
    return switch (type) {
      case "object" -> value instanceof Map;
      case "string" -> value instanceof String;
      case "array" -> value instanceof List;
      case "boolean" -> value instanceof Boolean;
      case "null" -> value == null;
      default -> true;
    };
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> ref(String ref) {
    Object node = SPEC;
    for (var part : ref.replaceFirst("^#/", "").split("/"))
      node = ((Map<String, Object>) node).get(part);
    return (Map<String, Object>) node;
  }

  private static Map<String, Object> schema(String name) {
    return ref("#/components/schemas/" + name);
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> vectors(String file) throws IOException {
    return JSON.readValue(Files.readString(CONTRACT.resolve(file)), Map.class);
  }

  private static Map<String, Object> spec() {
    try {
      return new Yaml().load(Files.readString(Path.of("docs/api/decision-api-v1.yaml")));
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }
}

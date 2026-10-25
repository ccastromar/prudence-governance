package cc.ccmlab.prudence.governance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.ObjectMapper;

/** Runs the governance contract's test vectors (contract/governance/vectors) against Prudence. */
class GovernanceVectorsTest {

  private static final Path VECTORS = Path.of("contract/governance/vectors");
  private static final ObjectMapper JSON = new ObjectMapper();

  @SuppressWarnings("unchecked")
  private static Map<String, Object> read(String file) throws IOException {
    return JSON.readValue(Files.readString(VECTORS.resolve(file)), Map.class);
  }

  @SuppressWarnings("unchecked")
  private static Object model(Map<String, Object> spec) throws IOException {
    if (spec.containsKey("file")) {
      return new org.yaml.snakeyaml.Yaml()
          .load(Files.newInputStream(VECTORS.resolve((String) spec.get("file"))));
    }
    return spec.get("inline");
  }

  @TestFactory
  @SuppressWarnings("unchecked")
  Stream<DynamicTest> resolution() throws IOException {
    Map<String, Object> data = read("resolution.json");
    Map<String, GovernanceModel> models = new LinkedHashMap<>();
    for (var e : ((Map<String, Object>) data.get("models")).entrySet()) {
      models.put(e.getKey(), GovernanceModel.of(model((Map<String, Object>) e.getValue())));
    }
    List<DynamicTest> tests = new ArrayList<>();
    for (Object c : (List<Object>) data.get("cases")) {
      var testCase = (Map<String, Object>) c;
      tests.add(
          dynamicTest(
              (String) testCase.get("name"),
              () ->
                  assertEquals(
                      testCase.get("expect"),
                      models
                          .get(testCase.get("model"))
                          .resolve(
                              (Map<String, Object>) testCase.get("claims"),
                              (String) testCase.get("app")))));
    }
    return tests.stream();
  }

  @TestFactory
  @SuppressWarnings("unchecked")
  Stream<DynamicTest> validation() throws IOException {
    Map<String, Object> data = read("validation.json");
    List<DynamicTest> tests = new ArrayList<>();
    for (Object c : (List<Object>) data.get("cases")) {
      var testCase = (Map<String, Object>) c;
      Object model =
          testCase.containsKey("model")
              ? model((Map<String, Object>) testCase.get("model"))
              : mergePatch(data.get("base"), testCase.get("patch"));
      tests.add(
          dynamicTest(
              (String) testCase.get("name"),
              () -> {
                if (Boolean.TRUE.equals(testCase.get("valid"))) {
                  GovernanceModel.of(model);
                } else {
                  var e =
                      assertThrows(GovernanceModelException.class, () -> GovernanceModel.of(model));
                  assertEquals(testCase.get("error"), e.code());
                }
              }));
    }
    return tests.stream();
  }

  @Test
  void theDefaultModelIsPackaged() throws IOException {
    GovernanceModel.load(
        new ClassPathResource(GovernanceConfiguration.DEFAULT_MODEL).getInputStream());
  }

  /** RFC 7386 JSON Merge Patch: objects merge, null removes, anything else replaces. */
  @SuppressWarnings("unchecked")
  static Object mergePatch(Object target, Object patch) {
    if (!(patch instanceof Map<?, ?> p)) return patch;
    Map<String, Object> result =
        target instanceof Map<?, ?> t
            ? new LinkedHashMap<>((Map<String, Object>) t)
            : new LinkedHashMap<>();
    for (var e : ((Map<String, Object>) p).entrySet()) {
      if (e.getValue() == null) result.remove(e.getKey());
      else result.put(e.getKey(), mergePatch(result.get(e.getKey()), e.getValue()));
    }
    return result;
  }
}

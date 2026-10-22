package cc.ccmlab.prudence.policy.application.bundle;

import cc.ccmlab.prudence.policy.adapter.in.web.PolicyDraftController.Rule;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.GZIPOutputStream;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/** Creates a deterministic OPA snapshot. The digest covers canonical rules, not itself. */
@Component
public class PolicyBundleBuilder {
  private final ObjectMapper json;
  private final byte[] rego;

  public record Bundle(String version, String digest, byte[] archive, byte[] canonicalRules) {}

  public PolicyBundleBuilder(ObjectMapper json) throws IOException {
    this.json = json;
    this.rego = new ClassPathResource("policy/managed/decision.rego").getContentAsByteArray();
  }

  public Bundle build(String version, List<Rule> rules) {
    if (version == null
        || version.isBlank()
        || rules == null
        || rules.isEmpty()
        || rules.size() > 100)
      throw new IllegalArgumentException("invalid bundle version or rule count");
    var ordered =
        rules.stream()
            .sorted(
                Comparator.comparing(Rule::role)
                    .thenComparing(Rule::action)
                    .thenComparing(Rule::pipeline)
                    .thenComparing(Rule::effect))
            .map(
                r -> {
                  if (r.role().isBlank()
                      || r.pipeline().isBlank()
                      || !"pipeline.run".equals(r.action())
                      || !List.of("ALLOW", "DENY").contains(r.effect()))
                    throw new IllegalArgumentException("invalid policy rule");
                  return new TreeMap<>(
                      Map.of(
                          "action",
                          r.action(),
                          "effect",
                          r.effect(),
                          "pipeline",
                          r.pipeline(),
                          "role",
                          r.role()));
                })
            .toList();
    byte[] canonical = json.writeValueAsBytes(ordered);
    String digest;
    try {
      digest =
          "sha256:"
              + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical));
      var data =
          json.writeValueAsBytes(
              Map.of("managed", Map.of("version", version, "digest", digest, "rules", ordered)));
      var manifest =
          json.writeValueAsBytes(
              Map.of("revision", version, "roots", List.of("managed", "prudence_managed")));
      var output = new ByteArrayOutputStream();
      try (var gzip = new GZIPOutputStream(output)) {
        entry(gzip, ".manifest", manifest);
        entry(gzip, "data.json", data);
        entry(gzip, "decision.rego", rego);
        gzip.write(new byte[1024]);
      }
      return new Bundle(version, digest, output.toByteArray(), canonical);
    } catch (IOException | NoSuchAlgorithmException ex) {
      throw new IllegalStateException("could not build policy bundle", ex);
    }
  }

  private static void entry(GZIPOutputStream out, String name, byte[] content) throws IOException {
    byte[] header = new byte[512];
    put(header, 0, 100, name);
    octal(header, 100, 8, 0644);
    octal(header, 108, 8, 0);
    octal(header, 116, 8, 0);
    octal(header, 124, 12, content.length);
    octal(header, 136, 12, 0);
    for (int i = 148; i < 156; i++) header[i] = ' ';
    header[156] = '0';
    put(header, 257, 6, "ustar");
    put(header, 263, 2, "00");
    int checksum = 0;
    for (byte b : header) checksum += b & 0xff;
    octal(header, 148, 8, checksum);
    out.write(header);
    out.write(content);
    int padding = (512 - content.length % 512) % 512;
    out.write(new byte[padding]);
  }

  private static void put(byte[] into, int start, int length, String value) {
    byte[] bytes = value.getBytes(StandardCharsets.US_ASCII);
    if (bytes.length > length) throw new IllegalArgumentException("tar field too long");
    System.arraycopy(bytes, 0, into, start, bytes.length);
  }

  private static void octal(byte[] into, int start, int length, long value) {
    put(into, start, length, String.format("%0" + (length - 1) + "o", value));
  }
}

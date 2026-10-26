package cc.ccmlab.prudence.evidence;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

/// How people appear in evidence that leaves Prudence (Ernest, a CloudEvents audit store): as a
/// keyed pseudonym, HMAC-SHA256 of their identity under the installation's pseudonym key, never by
/// name. Evidence stores are append-only and often readable by auditors and other teams; a name
/// there could never be erased, and a plain hash of a username is confirmed by guessing a few.
/// Prudence keeps who did what in its own records, so it can tell who a pseudonym is (it holds
/// the key and the people); nobody reading only the evidence can.
///
/// The key is `prudence.evidence.pseudonym-key` (PRUDENCE_EVIDENCE_PSEUDONYM_KEY) or a file
/// holding it, `prudence.evidence.pseudonym-key-file` (a vault or container secret), at least 32
/// bytes, required outside dev/test. Keep it: the same person must keep the same pseudonym.
@Component
public class EvidencePseudonyms {

  public static final int MIN_KEY_BYTES = 32;
  private static final Logger log = LoggerFactory.getLogger(EvidencePseudonyms.class);
  private static final String DEVELOPMENT_KEY = "development-only-evidence-pseudonym-key";

  private final byte[] key;

  @Autowired
  public EvidencePseudonyms(
      @Value("${prudence.evidence.pseudonym-key:}") String key,
      @Value("${prudence.evidence.pseudonym-key-file:}") String keyFile,
      Environment environment) {
    this.key = load(key, keyFile, environment.acceptsProfiles(Profiles.of("dev", "test")));
  }

  private EvidencePseudonyms(byte[] key) {
    this.key = key;
  }

  /// For adapters built outside Spring (tests).
  public static EvidencePseudonyms withKey(String key) {
    return new EvidencePseudonyms(key.getBytes(StandardCharsets.UTF_8));
  }

  /// The pseudonym of a person (a username, an email); null stays null.
  public String of(String person) {
    if (person == null) return null;
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(key, "HmacSHA256"));
      return HexFormat.of().formatHex(mac.doFinal(person.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception e) {
      throw new IllegalStateException("cannot compute an evidence pseudonym", e);
    }
  }

  static byte[] load(String key, String keyFile, boolean development) {
    String k = key == null ? "" : key.strip();
    String file = keyFile == null ? "" : keyFile.strip();
    if (!k.isEmpty() && !file.isEmpty()) {
      throw new IllegalStateException(
          "Set prudence.evidence.pseudonym-key or prudence.evidence.pseudonym-key-file, not both");
    }
    if (!file.isEmpty()) {
      try {
        k = Files.readString(Path.of(file)).strip();
      } catch (IOException e) {
        throw new IllegalStateException("Cannot read prudence.evidence.pseudonym-key-file", e);
      }
      if (k.isEmpty()) {
        throw new IllegalStateException(
            "prudence.evidence.pseudonym-key-file " + file + " is empty");
      }
    }
    if (k.isEmpty()) {
      if (!development) {
        throw new IllegalStateException(
            "Refusing to start outside dev/test without prudence.evidence.pseudonym-key"
                + " (PRUDENCE_EVIDENCE_PSEUDONYM_KEY) or prudence.evidence.pseudonym-key-file:"
                + " people in evidence are pseudonymised with it");
      }
      log.warn(
          "No evidence pseudonym key: using the development key. Set"
              + " PRUDENCE_EVIDENCE_PSEUDONYM_KEY outside dev/test.");
      k = DEVELOPMENT_KEY;
    }
    byte[] bytes = k.getBytes(StandardCharsets.UTF_8);
    if (!development && bytes.length < MIN_KEY_BYTES) {
      throw new IllegalStateException(
          "The evidence pseudonym key must be at least "
              + MIN_KEY_BYTES
              + " bytes outside dev/test");
    }
    return bytes;
  }
}

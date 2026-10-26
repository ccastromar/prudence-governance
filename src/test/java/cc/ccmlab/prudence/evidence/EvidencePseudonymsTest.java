package cc.ccmlab.prudence.evidence;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EvidencePseudonymsTest {

  private static final String KEY = "k".repeat(EvidencePseudonyms.MIN_KEY_BYTES);

  @Test
  void aPseudonymIsStableKeyedAndNotTheName() {
    var a = EvidencePseudonyms.withKey(KEY);
    var b = EvidencePseudonyms.withKey(KEY + "other");
    assertEquals(a.of("carol"), a.of("carol"));
    assertNotEquals(a.of("carol"), b.of("carol"));
    assertNotEquals(a.of("carol"), a.of("dave"));
    assertEquals(64, a.of("carol").length());
    assertNull(a.of(null));
  }

  @Test
  void theKeyComesFromTheVariableOrAMountedFile(@TempDir Path dir) throws Exception {
    Path file = dir.resolve("key");
    Files.writeString(file, KEY + "\n");
    assertArrayEquals(
        KEY.getBytes(StandardCharsets.UTF_8), EvidencePseudonyms.load(KEY, "", false));
    assertArrayEquals(
        KEY.getBytes(StandardCharsets.UTF_8), EvidencePseudonyms.load("", file.toString(), false));
    assertThrows(
        IllegalStateException.class, () -> EvidencePseudonyms.load(KEY, file.toString(), false));
  }

  @Test
  void outsideDevelopmentAKeyOfAtLeast32BytesIsRequired() {
    assertThrows(IllegalStateException.class, () -> EvidencePseudonyms.load("", "", false));
    assertThrows(IllegalStateException.class, () -> EvidencePseudonyms.load("short", "", false));
    EvidencePseudonyms.load("", "", true); // development key, with a warning
  }
}

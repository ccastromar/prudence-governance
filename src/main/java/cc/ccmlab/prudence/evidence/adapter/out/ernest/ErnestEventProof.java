package cc.ccmlab.prudence.evidence.adapter.out.ernest;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/// Independent check of an Ernest ADR-004 event proof (GET /api/events/{source}/{eventId}/proof),
/// so Prudence confirms evidence by verifying it rather than by trusting Ernest's word:
///
///  1. the block's data reproduces the block's hash (Ernest's block-hash law);
///  2. the event reaches that block — for a batch block, its data's RFC 6962 leaf
///     (SHA-256(0x00 || JCS(data))) climbs the audit path to the root committed in the
///     block; for a per-event block, the event data IS the block data.
///
/// Pinned to Ernest's implementations by its testdata/rfc6962-golden.json.
public final class ErnestEventProof {

  private static final String TREE_ALG = "rfc6962-sha256";
  /// Keys Ernest's hash law drops at every depth (json-canonicalize `exclude`).
  private static final Set<String> EXCLUDED_KEYS =
      Set.of("__v", "_id", "createdAt", "updatedAt", "hash");

  private ErnestEventProof() {}

  /// `valid` with the block that chains the event; otherwise `reason` says what failed.
  public record Result(
      boolean valid, Long blockIndex, Map<String, Object> eventData, String reason) {
    static Result invalid(String reason) {
      return new Result(false, null, null, reason);
    }
  }

  @SuppressWarnings("unchecked")
  public static Result verify(Map<String, Object> proof) {
    try {
      if (!"chained".equals(proof.get("status"))) return Result.invalid("event is not chained yet");
      Map<String, Object> event = (Map<String, Object>) proof.get("event");
      Map<String, Object> inclusion = (Map<String, Object>) proof.get("inclusion");
      Map<String, Object> block = (Map<String, Object>) proof.get("block");
      Map<String, Object> eventData = (Map<String, Object>) event.get("data");
      Map<String, Object> blockData = (Map<String, Object>) block.get("data");
      long index = ((Number) block.get("index")).longValue();

      String blockHash =
          sha256Hex(
              (index
                      + "|"
                      + ((Number) block.get("timestamp")).longValue()
                      + "|"
                      + canonical(blockData)
                      + "|"
                      + block.get("previousHash"))
                  .getBytes(StandardCharsets.UTF_8));
      if (!blockHash.equals(block.get("hash")))
        return Result.invalid("block data does not reproduce its hash");

      switch (String.valueOf(inclusion.get("kind"))) {
        case "batch" -> {
          byte[] leaf =
              sha256(new byte[] {0x00}, canonical(eventData).getBytes(StandardCharsets.UTF_8));
          long leafIndex = ((Number) inclusion.get("leafIndex")).longValue();
          long size = ((Number) inclusion.get("treeSize")).longValue();
          String root = (String) inclusion.get("root");
          boolean commits =
              "batch".equals(blockData.get("type"))
                  && TREE_ALG.equals(blockData.get("treeAlg"))
                  && root.equals(blockData.get("root"))
                  && blockData.get("size") instanceof Number s
                  && s.longValue() == size;
          if (!commits) return Result.invalid("block does not commit to the proof's batch root");
          if (!verifyInclusion(
              leaf, leafIndex, size, (List<String>) inclusion.get("auditPath"), root)) {
            return Result.invalid("audit path does not reach the batch root");
          }
        }
        case "block" -> {
          if (!canonical(eventData).equals(canonical(blockData))) {
            return Result.invalid("event data differs from the block's");
          }
        }
        default -> {
          return Result.invalid("unknown inclusion kind " + inclusion.get("kind"));
        }
      }
      return new Result(true, index, eventData, null);
    } catch (RuntimeException e) {
      return Result.invalid("malformed proof: " + e.getMessage());
    }
  }

  static String canonical(Object value) {
    return ErnestSealer.canonicalize(withoutExcludedKeys(value));
  }

  private static Object withoutExcludedKeys(Object value) {
    if (value instanceof Map<?, ?> m) {
      Map<String, Object> out = new LinkedHashMap<>();
      m.forEach(
          (k, v) -> {
            if (!EXCLUDED_KEYS.contains((String) k)) out.put((String) k, withoutExcludedKeys(v));
          });
      return out;
    }
    if (value instanceof List<?> l)
      return l.stream().map(ErnestEventProof::withoutExcludedKeys).toList();
    return value;
  }

  /// RFC 9162 §2.1.3.2.
  static boolean verifyInclusion(
      byte[] leaf, long index, long size, List<String> path, String root) {
    if (index < 0 || index >= size) return false;
    HexFormat hex = HexFormat.of();
    long fn = index, sn = size - 1;
    byte[] r = leaf;
    for (String h : path) {
      byte[] p = hex.parseHex(h);
      if (sn == 0) return false;
      if (fn % 2 == 1 || fn == sn) {
        r = sha256(new byte[] {0x01}, p, r);
        while (fn % 2 == 0 && fn != 0) {
          fn >>= 1;
          sn >>= 1;
        }
      } else {
        r = sha256(new byte[] {0x01}, r, p);
      }
      fn >>= 1;
      sn >>= 1;
    }
    return sn == 0 && hex.formatHex(r).equals(root);
  }

  private static byte[] sha256(byte[]... parts) {
    try {
      MessageDigest md = MessageDigest.getInstance("SHA-256");
      for (byte[] p : parts) md.update(p);
      return md.digest();
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private static String sha256Hex(byte[] data) {
    return HexFormat.of().formatHex(sha256(data));
  }
}

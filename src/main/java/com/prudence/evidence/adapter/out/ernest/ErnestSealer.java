package com.prudence.evidence.adapter.out.ernest;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.web.client.RestClient;

/// ADR-002 B2 sealer for the control plane: produces a qualified-electronic-seal envelope
/// over the EXACT bytes Ernest canonicalizes and verifies — block data cleaned the way
/// Ernest's `cleanObject` cleans it, JCS-canonicalized (RFC 8785), wrapped in a DSSE PAE.
/// The signature is produced by a QTSP CSC remote-signing endpoint, so no private key lives
/// in Prudence. Mirrors `signing.util.ts` / `prudence_governance/seal.py` byte-for-byte.
public class ErnestSealer {

  private static final String PAYLOAD_TYPE = "application/vnd.ernest.provenance+json";

  private final RestClient csc;

  public ErnestSealer(String cscUrl) {
    this.csc = RestClient.builder().baseUrl(cscUrl).build();
  }

  /// Seal the given block data: returns the envelope to embed as the submission `signature`.
  public Map<String, Object> seal(Map<String, Object> blockData) {
    String b64 = Base64.getEncoder().encodeToString(signedBytes(blockData));
    @SuppressWarnings("unchecked")
    Map<String, Object> envelope = csc.post().body(Map.of("bytes", b64)).retrieve().body(Map.class);
    return envelope;
  }

  // ── byte construction (must match Ernest exactly) ──────────────────────────

  static byte[] signedBytes(Map<String, Object> data) {
    Object cleaned = clean(data);
    if (cleaned instanceof Map<?, ?> m) {
      // defensive: the signed bytes never include the signature field
      ((Map<?, ?>) m).remove("signature");
    }
    byte[] body = canonicalize(cleaned).getBytes(StandardCharsets.UTF_8);
    String header =
        "DSSEv1 " + PAYLOAD_TYPE.length() + " " + PAYLOAD_TYPE + " " + body.length + " ";
    byte[] h = header.getBytes(StandardCharsets.UTF_8);
    byte[] out = new byte[h.length + body.length];
    System.arraycopy(h, 0, out, 0, h.length);
    System.arraycopy(body, 0, out, h.length, body.length);
    return out;
  }

  /// Replicates Ernest's cleanObject: drop null / empty-string values and empty
  /// nested objects/arrays, recursively.
  @SuppressWarnings("unchecked")
  static Object clean(Object v) {
    if (v == null) return null;
    if (v instanceof Map<?, ?> map) {
      Map<String, Object> out = new LinkedHashMap<>();
      for (Map.Entry<?, ?> e : map.entrySet()) {
        Object cv = e.getValue();
        if (cv == null || "".equals(cv)) continue;
        if (cv instanceof Map || cv instanceof List) {
          Object cc = clean(cv);
          if (cc instanceof Map<?, ?> cm && !cm.isEmpty()) out.put((String) e.getKey(), cc);
          else if (cc instanceof List<?> cl && !cl.isEmpty()) out.put((String) e.getKey(), cc);
        } else {
          out.put((String) e.getKey(), cv);
        }
      }
      return out;
    }
    if (v instanceof List<?> list) {
      List<Object> out = new ArrayList<>();
      for (Object it : list) {
        Object ci = clean(it);
        if (ci != null) out.add(ci);
      }
      return out;
    }
    return v;
  }

  /// SHA-256 hex of the canonical form of a String/Map/List document — a deterministic
  /// content hash reused for attestation reports.
  public static String sha256Canonical(Object document) {
    try {
      byte[] d =
          MessageDigest.getInstance("SHA-256")
              .digest(canonicalize(document).getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(d);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  /// RFC 8785 JSON Canonicalization for the String/Number/Map/List payloads Ernest signs
  /// (keys sorted by UTF-16 code unit, minimal separators, RFC 8785 string escaping).
  public static String canonicalize(Object v) {
    StringBuilder sb = new StringBuilder();
    write(sb, v);
    return sb.toString();
  }

  private static void write(StringBuilder sb, Object v) {
    switch (v) {
      case null -> sb.append("null");
      case String s -> writeString(sb, s);
      case Boolean b -> sb.append(b ? "true" : "false");
      case Number n -> writeNumber(sb, n.doubleValue());
      case Map<?, ?> m -> {
        List<String> keys = new ArrayList<>();
        for (Object k : m.keySet()) keys.add((String) k);
        Collections.sort(keys); // String natural order == UTF-16 code-unit order
        sb.append('{');
        for (int i = 0; i < keys.size(); i++) {
          if (i > 0) sb.append(',');
          writeString(sb, keys.get(i));
          sb.append(':');
          write(sb, m.get(keys.get(i)));
        }
        sb.append('}');
      }
      case List<?> l -> {
        sb.append('[');
        for (int i = 0; i < l.size(); i++) {
          if (i > 0) sb.append(',');
          write(sb, l.get(i));
        }
        sb.append(']');
      }
      default ->
          throw new IllegalArgumentException(
              "unsupported canonical type: " + v.getClass().getName());
    }
  }

  /// RFC 8785 §3.2.2.3: numbers are IEEE-754 doubles serialized like ECMAScript's
  /// Number.prototype.toString. Java's Double.toString yields the shortest round-tripping
  /// digits (JDK 19+); only the layout (plain vs exponent, "e+21" style) is rewritten here.
  private static void writeNumber(StringBuilder sb, double d) {
    if (Double.isNaN(d) || Double.isInfinite(d)) {
      throw new IllegalArgumentException("non-finite numbers cannot be canonicalized");
    }
    if (d == 0) {
      sb.append('0'); // also -0
      return;
    }
    if (d < 0) {
      sb.append('-');
      d = -d;
    }
    java.math.BigDecimal bd = new java.math.BigDecimal(Double.toString(d)).stripTrailingZeros();
    String digits = bd.unscaledValue().toString();
    int k = digits.length();
    int n = k - bd.scale(); // decimal exponent: value = 0.digits × 10^n
    if (k <= n && n <= 21) {
      sb.append(digits).append("0".repeat(n - k));
    } else if (0 < n && n <= 21) {
      sb.append(digits, 0, n).append('.').append(digits, n, k);
    } else if (-6 < n && n <= 0) {
      sb.append("0.").append("0".repeat(-n)).append(digits);
    } else {
      sb.append(digits.charAt(0));
      if (k > 1) sb.append('.').append(digits, 1, k);
      int e = n - 1;
      sb.append('e').append(e < 0 ? '-' : '+').append(Math.abs(e));
    }
  }

  private static void writeString(StringBuilder sb, String s) {
    sb.append('"');
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      switch (c) {
        case '"' -> sb.append("\\\"");
        case '\\' -> sb.append("\\\\");
        case '\b' -> sb.append("\\b");
        case '\t' -> sb.append("\\t");
        case '\n' -> sb.append("\\n");
        case '\f' -> sb.append("\\f");
        case '\r' -> sb.append("\\r");
        default -> {
          if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
          else sb.append(c);
        }
      }
    }
    sb.append('"');
  }
}

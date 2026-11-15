package cc.ccmlab.prudence.governance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.TextNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.List;

/** JSON helpers: one mapper, and the canonical form the Python core hashes. */
public final class Json {
  static final ObjectMapper MAPPER = new ObjectMapper();

  private Json() {}

  /** A tool's arguments as a tree: parsed when they are a JSON string, as text otherwise. */
  public static JsonNode tree(Object value) {
    if (value instanceof JsonNode node) return node;
    if (value instanceof String text) {
      try {
        return MAPPER.readTree(text);
      } catch (Exception e) {
        return TextNode.valueOf(text);
      }
    }
    return MAPPER.valueToTree(value);
  }

  /**
   * Canonical JSON as the Python core writes it (sorted keys, no spaces, non-ASCII escaped as
   * lowercase {@code \\uXXXX}), so the same inputs give the same hash in both.
   */
  public static String canonical(Object value) {
    StringBuilder out = new StringBuilder();
    write(tree(value instanceof String s ? TextNode.valueOf(s) : value), out);
    return out.toString();
  }

  public static String sha256(Object value) {
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256")
              .digest(canonical(value).getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(digest);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  private static void write(JsonNode node, StringBuilder out) {
    if (node == null || node.isNull() || node.isMissingNode()) {
      out.append("null");
    } else if (node.isObject()) {
      List<String> keys = new ArrayList<>();
      Iterator<String> names = node.fieldNames();
      names.forEachRemaining(keys::add);
      Collections.sort(keys);
      out.append('{');
      for (int i = 0; i < keys.size(); i++) {
        if (i > 0) out.append(',');
        string(keys.get(i), out);
        out.append(':');
        write(node.get(keys.get(i)), out);
      }
      out.append('}');
    } else if (node.isArray()) {
      out.append('[');
      for (int i = 0; i < node.size(); i++) {
        if (i > 0) out.append(',');
        write(node.get(i), out);
      }
      out.append(']');
    } else if (node.isTextual()) {
      string(node.textValue(), out);
    } else {
      out.append(node.toString());
    }
  }

  private static void string(String text, StringBuilder out) {
    out.append('"');
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      switch (c) {
        case '"' -> out.append("\\\"");
        case '\\' -> out.append("\\\\");
        case '\n' -> out.append("\\n");
        case '\r' -> out.append("\\r");
        case '\t' -> out.append("\\t");
        case '\b' -> out.append("\\b");
        case '\f' -> out.append("\\f");
        default -> {
          if (c < 0x20 || c > 0x7e) out.append(String.format("\\u%04x", (int) c));
          else out.append(c);
        }
      }
    }
    out.append('"');
  }
}

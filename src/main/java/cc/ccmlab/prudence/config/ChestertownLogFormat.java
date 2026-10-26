package cc.ccmlab.prudence.config;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.classic.spi.StackTraceElementProxy;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.slf4j.event.KeyValuePair;
import org.springframework.boot.logging.structured.StructuredLogFormatter;
import org.springframework.core.env.Environment;
import tools.jackson.databind.json.JsonMapper;

/// One JSON object per log line with the suite's field names (contract/observability, section
/// 5): `timestamp`, `severity`, `service.name`, `service.version`,
/// `deployment.environment.name`, `message`, `trace_id`, `span_id`, `correlation_id`, and the
/// key-value pairs a log call adds (`event.name`, `outcome`, `decision_id`…).
///
/// A throwable becomes `error.type` (its class) and `exception.stacktrace` with classes and
/// frames only: an exception's message may carry what a person typed (section 7), so it is
/// never written. The message of the log call itself is written as given; log calls keep user
/// content out of it.
///
/// Enabled by `logging.structured.format.console` (application.yml); the dev profile keeps text.
public class ChestertownLogFormat implements StructuredLogFormatter<ILoggingEvent> {

  private static final DateTimeFormatter TIMESTAMP =
      DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

  /// MDC keys and the field each becomes; other MDC keys are not written.
  private static final Map<String, String> MDC_FIELDS =
      Map.of(
          "traceId",
          "trace_id",
          "spanId",
          "span_id",
          CorrelationIdFilter.MDC_KEY,
          "correlation_id");

  /// Fields a key-value pair may not overwrite.
  private static final Set<String> RESERVED =
      Set.of(
          "timestamp",
          "severity",
          "service.name",
          "service.version",
          "deployment.environment.name",
          "message",
          "trace_id",
          "span_id");

  private static final JsonMapper JSON = JsonMapper.builder().build();

  private final String serviceName;
  private final String serviceVersion;
  private final String environment;

  public ChestertownLogFormat(Environment env) {
    this(
        env.getProperty("spring.application.name", "prudence"),
        env.getProperty("spring.application.version", "dev"),
        env.getProperty("prudence.observability.environment", "production"));
  }

  ChestertownLogFormat(String serviceName, String serviceVersion, String environment) {
    this.serviceName = serviceName;
    this.serviceVersion = serviceVersion;
    this.environment = environment;
  }

  @Override
  public String format(ILoggingEvent event) {
    Map<String, Object> line = new LinkedHashMap<>();
    line.put("timestamp", TIMESTAMP.format(event.getInstant()));
    line.put("severity", severity(event.getLevel()));
    line.put("service.name", serviceName);
    line.put("service.version", serviceVersion);
    line.put("deployment.environment.name", environment);
    line.put("message", event.getFormattedMessage());
    line.put("log.logger", event.getLoggerName());
    Map<String, String> mdc = event.getMDCPropertyMap();
    MDC_FIELDS.forEach(
        (key, field) -> {
          String value = mdc.get(key);
          if (value != null && !value.isBlank()) line.put(field, value);
        });
    if (event.getKeyValuePairs() != null) {
      for (KeyValuePair pair : event.getKeyValuePairs()) {
        if (pair.key != null && !RESERVED.contains(pair.key) && pair.value != null) {
          line.put(pair.key, pair.value);
        }
      }
    }
    IThrowableProxy error = event.getThrowableProxy();
    if (error != null) {
      line.putIfAbsent("error.type", error.getClassName());
      line.put("exception.stacktrace", stackTrace(error));
    }
    return JSON.writeValueAsString(line) + "\n";
  }

  static String severity(Level level) {
    return switch (level.toInt()) {
      case Level.ERROR_INT -> "ERROR";
      case Level.WARN_INT -> "WARN";
      case Level.INFO_INT -> "INFO";
      case Level.DEBUG_INT -> "DEBUG";
      default -> "TRACE";
    };
  }

  /// Classes and frames of the throwable and its causes, without their messages.
  static String stackTrace(IThrowableProxy error) {
    StringBuilder out = new StringBuilder();
    for (IThrowableProxy t = error; t != null; t = t.getCause()) {
      if (t != error) out.append("Caused by: ");
      out.append(t.getClassName()).append('\n');
      StackTraceElementProxy[] frames = t.getStackTraceElementProxyArray();
      int shown = frames.length - t.getCommonFrames();
      for (int i = 0; i < shown; i++)
        out.append("\tat ")
            .append(frames[i].getSTEAsString().replaceFirst("^at ", ""))
            .append('\n');
      if (t.getCommonFrames() > 0)
        out.append("\t... ").append(t.getCommonFrames()).append(" more\n");
    }
    return out.toString();
  }
}

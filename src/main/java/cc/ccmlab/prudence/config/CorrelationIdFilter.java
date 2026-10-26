package cc.ccmlab.prudence.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.regex.Pattern;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/// The caller's business correlation id (`X-Correlation-Id`, contract/observability section 3)
/// for the duration of a request: in the logging context as `correlation_id`, so every log line
/// of the request carries it, and readable by the code that records it (a decision's
/// correlationId). An id that is not a plain token is ignored rather than logged.
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class CorrelationIdFilter extends OncePerRequestFilter {

  public static final String HEADER = "X-Correlation-Id";
  public static final String MDC_KEY = "correlation_id";
  private static final Pattern TOKEN = Pattern.compile("[A-Za-z0-9._:-]{1,128}");

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String id = request.getHeader(HEADER);
    if (id == null || !TOKEN.matcher(id).matches()) {
      chain.doFilter(request, response);
      return;
    }
    MDC.put(MDC_KEY, id);
    try {
      chain.doFilter(request, response);
    } finally {
      MDC.remove(MDC_KEY);
    }
  }

  /// The current request's correlation id, or null.
  public static String current() {
    return MDC.get(MDC_KEY);
  }
}

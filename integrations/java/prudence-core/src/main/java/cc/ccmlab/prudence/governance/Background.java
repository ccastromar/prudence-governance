package cc.ccmlab.prudence.governance;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Where reports and evidence run: off the tool's path, on daemon threads, and drained for up to ten
 * seconds when the JVM exits, so a short-lived process does not lose its last report.
 */
final class Background {
  private static final AtomicInteger COUNT = new AtomicInteger();
  private static final ThreadFactory THREADS =
      r -> {
        Thread t = new Thread(r, "prudence-background-" + COUNT.incrementAndGet());
        t.setDaemon(true);
        return t;
      };
  private static final ExecutorService EXECUTOR = Executors.newCachedThreadPool(THREADS);

  static {
    Runtime.getRuntime()
        .addShutdownHook(
            new Thread(
                () -> {
                  EXECUTOR.shutdown();
                  try {
                    EXECUTOR.awaitTermination(10, TimeUnit.SECONDS);
                  } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                  }
                },
                "prudence-background-drain"));
  }

  private Background() {}

  static void run(Runnable task) {
    try {
      EXECUTOR.execute(task);
    } catch (RuntimeException e) {
      // Shutting down: best effort means dropping it, never failing the tool.
    }
  }
}

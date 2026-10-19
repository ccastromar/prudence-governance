package cc.ccmlab.prudence.alerting.application.port.out;

import cc.ccmlab.prudence.alerting.application.port.in.AlertQueries.AlertView;
import cc.ccmlab.prudence.alerting.domain.AlertKind;
import java.util.List;

/// Driven port: durable alert storage. A stored alert is pending until the delivery worker
/// has sent it.
public interface AlertStore {
  boolean exists(String dedupKey);

  void add(
      String id,
      AlertKind kind,
      String dedupKey,
      String aiSystemId,
      List<String> recipients,
      String subject,
      String body);

  List<AlertView> recent(int limit);
}

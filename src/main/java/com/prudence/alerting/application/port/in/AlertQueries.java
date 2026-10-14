package com.prudence.alerting.application.port.in;

import java.util.List;

/// Read side: the alerts Prudence has raised and whether each one was actually delivered.
public interface AlertQueries {
  List<AlertView> recent(int limit);

  record AlertView(
      String id,
      String kind,
      String aiSystemId,
      String subject,
      List<String> recipients,
      String createdAt,
      String delivery,
      String lastError) {}
}

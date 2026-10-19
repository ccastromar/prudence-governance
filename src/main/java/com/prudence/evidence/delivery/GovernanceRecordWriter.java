package com.prudence.evidence.delivery;

import java.util.Map;

/// Records a governance record (an incident event, a review…) in the configured evidence store
/// under a stable id. `content` is what is hashed and so proved; `metadata` travels alongside.
/// Throws on failure, for the delivery worker to classify and retry.
public interface GovernanceRecordWriter {

  /// `qualified`: carries a QTSP seal. `blockIndex`: where it landed, when the store says.
  record Receipt(boolean qualified, Long blockIndex) {}

  Receipt record(
      String registerId,
      String registerName,
      String recordId,
      Map<String, Object> content,
      Map<String, Object> metadata)
      throws Exception;
}

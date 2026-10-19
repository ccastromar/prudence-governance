package cc.ccmlab.prudence.registry.application.port.out;

import cc.ccmlab.prudence.registry.domain.AiSystem;
import cc.ccmlab.prudence.registry.domain.AiSystemId;
import cc.ccmlab.prudence.registry.domain.ApprovalStatus;
import java.util.List;
import java.util.Optional;

/// Driven port: persistence of the AI-system aggregate. Implemented by an adapter.
public interface AiSystemRepository {
  AiSystem save(AiSystem system);

  Optional<AiSystem> findById(AiSystemId id);

  /// Loads the system and locks it for the rest of the transaction, so concurrent state
  /// changes (two reviewers, two lifecycle sweeps) are serialised, never interleaved.
  Optional<AiSystem> findByIdForUpdate(AiSystemId id);

  List<AiSystemId> findIdsByStatus(ApprovalStatus status);

  List<AiSystem> findAll();
}

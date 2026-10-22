package cc.ccmlab.prudence.policy.adapter.out.persistence;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PolicyDraftEventRepository extends JpaRepository<PolicyDraftEventEntity, String> {
  boolean existsByRevisionIdAndActor(String revisionId, String actor);

  List<PolicyDraftEventEntity> findByRevisionIdOrderByOccurredAtAsc(String revisionId);
}

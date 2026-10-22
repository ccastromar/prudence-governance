package cc.ccmlab.prudence.policy.adapter.out.persistence;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PolicyRevisionRepository extends JpaRepository<PolicyRevisionEntity, String> {
  List<PolicyRevisionEntity> findByPolicyIdOrderByRevisionNumberDesc(String policyId);
}

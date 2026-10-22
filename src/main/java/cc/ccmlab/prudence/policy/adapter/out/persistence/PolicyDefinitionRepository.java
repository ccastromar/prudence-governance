package cc.ccmlab.prudence.policy.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

public interface PolicyDefinitionRepository extends JpaRepository<PolicyDefinitionEntity, String> {}

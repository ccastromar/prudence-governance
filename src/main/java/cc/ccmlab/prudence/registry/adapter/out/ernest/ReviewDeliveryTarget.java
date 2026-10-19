package cc.ccmlab.prudence.registry.adapter.out.ernest;

import cc.ccmlab.prudence.evidence.delivery.Delivery;
import cc.ccmlab.prudence.evidence.delivery.DeliveryTarget;
import cc.ccmlab.prudence.registry.adapter.out.persistence.ReviewEventJpaEntity;
import cc.ccmlab.prudence.registry.adapter.out.persistence.ReviewEventJpaRepository;
import cc.ccmlab.prudence.registry.adapter.out.persistence.ReviewPersistenceAdapter;
import cc.ccmlab.prudence.registry.application.port.out.ReviewSealPort;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Component;

/// Governance review events → Ernest (model "prudence-reviews"), under the review id.
@Component
public class ReviewDeliveryTarget implements DeliveryTarget<ReviewEventJpaEntity> {

  public static final String NAME = "reviews";

  private final ReviewEventJpaRepository reviews;
  private final ReviewSealPort seal;

  public ReviewDeliveryTarget(ReviewEventJpaRepository reviews, ReviewSealPort seal) {
    this.reviews = reviews;
    this.seal = seal;
  }

  @Override
  public String name() {
    return NAME;
  }

  @Override
  public List<ReviewEventJpaEntity> due(String now, int limit) {
    return reviews.due(now, Limit.of(limit));
  }

  @Override
  public String id(ReviewEventJpaEntity e) {
    return e.getId();
  }

  @Override
  public Delivery delivery(ReviewEventJpaEntity e) {
    return e.getDelivery();
  }

  @Override
  public int claim(String id, String expected, String leaseUntil) {
    return reviews.claim(id, expected, leaseUntil);
  }

  @Override
  public Optional<ReviewEventJpaEntity> find(String id) {
    return reviews.findById(id);
  }

  @Override
  public List<ReviewEventJpaEntity> deadLettered(int limit) {
    return reviews.deadLettered(Limit.of(limit));
  }

  @Override
  public String aiSystemId(ReviewEventJpaEntity e) {
    return e.getAiSystemId();
  }

  @Override
  public void save(ReviewEventJpaEntity e) {
    reviews.save(e);
  }

  @Override
  public Consumer<ReviewEventJpaEntity> send(ReviewEventJpaEntity e) throws Exception {
    ReviewSealPort.SealResult result = seal.seal(ReviewPersistenceAdapter.toDomain(e));
    return record -> record.recordSeal(result.qualified(), result.blockIndex());
  }
}

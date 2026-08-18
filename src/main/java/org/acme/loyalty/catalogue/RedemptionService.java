package org.acme.loyalty.catalogue;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.LockModeType;
import jakarta.transaction.Transactional;
import java.time.Instant;

@ApplicationScoped
public class RedemptionService {

    public enum Result { CONFIRMED, INSUFFICIENT_POINTS, UNKNOWN_REWARD, DUPLICATE }

    public record Outcome(Result result, int newBalance, int shortfall) {}

    @Transactional
    public Outcome redeem(String memberId, String rewardName, String idempotencyKey) {

        Reward reward = Catalogue.byName(rewardName);
        if (reward == null) return new Outcome(Result.UNKNOWN_REWARD, -1, 0);

        if (Redemption.count("idempotencyKey", idempotencyKey) > 0) {
            Member existing = Member.findById(memberId);
            return new Outcome(Result.DUPLICATE, existing.points, 0);
        }

        // The balance in MemberContext is a snapshot taken before three model
        // calls happened. Lock and re-read before spending.
        Member member = Member.findById(memberId, LockModeType.PESSIMISTIC_WRITE);
        if (member == null) return new Outcome(Result.UNKNOWN_REWARD, -1, 0);

        if (member.points < reward.cost()) {
            return new Outcome(Result.INSUFFICIENT_POINTS, member.points,
                               reward.cost() - member.points);
        }

        member.points -= reward.cost();

        Redemption record = new Redemption();
        record.memberId = memberId;
        record.rewardName = reward.name();
        record.cost = reward.cost();          // catalogue price, never the model's
        record.idempotencyKey = idempotencyKey;
        record.createdAt = Instant.now();
        record.persist();

        return new Outcome(Result.CONFIRMED, member.points, 0);
    }
}

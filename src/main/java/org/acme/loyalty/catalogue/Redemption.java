package org.acme.loyalty.catalogue;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "redemption")
public class Redemption extends PanacheEntityBase {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) public Long id;
    @Column(name = "member_id",   nullable = false) public String memberId;
    @Column(name = "reward_name", nullable = false) public String rewardName;
    @Column(nullable = false) public int cost;
    @Column(name = "idempotency_key", nullable = false) public String idempotencyKey;
    @Column(name = "created_at",      nullable = false) public Instant createdAt;
}

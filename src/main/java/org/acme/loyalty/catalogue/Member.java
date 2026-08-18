package org.acme.loyalty.catalogue;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;

@Entity
@Table(name = "loyalty_member")
public class Member extends PanacheEntityBase {

    @Id public String id;
    @Column(nullable = false) public int points;
    @Version public long version;

    public String tier() {
        if (points >= 2000) return "GOLD";
        if (points >= 500)  return "SILVER";
        return "BRONZE";
    }
}

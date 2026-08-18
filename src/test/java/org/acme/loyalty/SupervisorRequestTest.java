package org.acme.loyalty;

import org.acme.loyalty.agents.LoyaltySupervisorAgent;
import org.acme.loyalty.catalogue.Catalogue;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** No cluster, no API key, no model. Runs in milliseconds. */
class SupervisorRequestTest {

    private static MemberContext member(int points) {
        return new MemberContext("m-test", points, "BRONZE", List.of(), List.of(),
                                 Catalogue.render());
    }

    @Test
    void forbidsRedemptionWhenNothingIsAffordable() {
        assertTrue(LoyaltySupervisorAgent
                .request(member(10), "redeem the travel voucher")
                .contains("DO NOT invoke RedemptionProposalAgent"));
    }

    @Test
    void allowsRedemptionAtExactlyTheCheapestCost() {
        assertFalse(LoyaltySupervisorAgent
                .request(member(Catalogue.cheapestCost()), "redeem a coffee")
                .contains("DO NOT invoke RedemptionProposalAgent"));
    }

    @Test
    void blocksOnePointBelowTheCheapestCost() {
        assertTrue(LoyaltySupervisorAgent
                .request(member(Catalogue.cheapestCost() - 1), "redeem")
                .contains("DO NOT invoke RedemptionProposalAgent"));
    }

    @Test
    void carriesTheMemberBalance() {
        assertTrue(LoyaltySupervisorAgent
                .request(member(742), "hi")
                .contains("742 points"));
    }
}

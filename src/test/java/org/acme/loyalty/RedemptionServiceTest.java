package org.acme.loyalty;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.acme.loyalty.catalogue.RedemptionService;
import org.acme.loyalty.catalogue.RedemptionService.Result;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.*;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class RedemptionServiceTest {

    @Inject RedemptionService service;

    @Test
    void chargesCataloguePriceNotProposedPrice() {
        var outcome = service.redeem("alice", "Travel voucher", UUID.randomUUID().toString());
        assertEquals(Result.CONFIRMED, outcome.result());
        assertEquals(250, outcome.newBalance());   // 2450 - 2200
    }

    @Test
    void rejectsUnknownReward() {
        assertEquals(Result.UNKNOWN_REWARD,
                service.redeem("alice", "Free Ferrari", UUID.randomUUID().toString()).result());
    }

    @Test
    void isIdempotent() {
        String key = UUID.randomUUID().toString();
        service.redeem("bob", "Free filter coffee", key);
        assertEquals(Result.DUPLICATE,
                service.redeem("bob", "Free filter coffee", key).result());
    }

    /**
     * carol holds 620 points; the reward costs 450, so exactly one
     * of eight concurrent attempts may succeed. Run this repeatedly — and once
     * with PESSIMISTIC_WRITE removed, to confirm it then fails.
     */
    @Test
    void onlyOneOfEightConcurrentRedemptionsSucceeds() throws Exception {
        var pool = Executors.newFixedThreadPool(8);
        var futures = pool.invokeAll(IntStream.range(0, 8)
                .mapToObj(i -> (Callable<Result>) () ->
                        service.redeem("carol", "Free specialty drink",
                                       UUID.randomUUID().toString()).result())
                .toList());

        long confirmed = futures.stream().filter(f -> get(f) == Result.CONFIRMED).count();
        pool.shutdown();
        assertEquals(1, confirmed);
    }

    private static Result get(Future<Result> f) {
        try { return f.get(); } catch (Exception e) { throw new RuntimeException(e); }
    }
}

package com.flashseats.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import com.flashseats.catalog.facade.CatalogFacade;
import com.flashseats.catalog.facade.ReserveResult;
import com.flashseats.flashseats.support.IntegrationTest;
import com.flashseats.flashseats.support.SaleFixture;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The test the product exists to pass.
 *
 * <p>Every other guarantee is downstream of this one: if two buyers can both take the last seat,
 * nothing else matters.
 *
 * <p>It runs against a real Redis because the guarantee <em>is</em> the atomicity of
 * {@code stock_reserve.lua} — the read, the comparison and the decrement are one step that nothing
 * can interleave with. A fake that ran the three separately would pass every test here and oversell
 * in production, which is the same reason this suite has always insisted on real containers.
 */
@DisplayName("Inventory cannot be oversold, however many buyers race for it")
class StockReserveConcurrencyIT extends IntegrationTest {

    @Autowired
    private CatalogFacade catalog;

    @Autowired
    private SaleFixture fixture;

    private long eventId;
    private long tierId;

    @BeforeEach
    void seedSale() {
        fixture.reset();
        eventId = fixture.openEvent("Concurrency Test");
        tierId = fixture.tier(eventId, "General Admission", 2_500, 1);
    }

    @Test
    @DisplayName("50 threads race for the final seat: exactly one wins")
    void exactlyOneBuyerGetsTheLastSeat() throws Exception {
        AtomicInteger reserved = countSuccessfulReserves(50, 1);

        assertThat(reserved.get()).isEqualTo(1);
        assertThat(fixture.remaining(tierId)).isZero();
    }

    @Test
    @DisplayName("100 threads against 30 seats: exactly 30 sell, and not one more")
    void neverSellsMoreThanCapacity() throws Exception {
        tierId = fixture.tier(eventId, "Floor", 4_500, 30);

        AtomicInteger reserved = countSuccessfulReserves(100, 1);

        assertThat(reserved.get()).isEqualTo(30);
        assertThat(fixture.remaining(tierId)).isZero();
    }

    @Test
    @DisplayName("1,000 concurrent buyers against 100 seats sell exactly 100")
    void oneThousandBuyersSellExactlyCapacity() throws Exception {
        // The Phase 2 exit criterion. The PostgreSQL counter was already correct at this level and
        // far slower; what has to survive the move to Redis is the correctness, not the speed.
        tierId = fixture.tier(eventId, "Stalls", 3_000, 100);

        AtomicInteger reserved = countSuccessfulReserves(1_000, 1);

        assertThat(reserved.get()).isEqualTo(100);
        assertThat(fixture.remaining(tierId)).isZero();
    }

    @Test
    @DisplayName("Partial demand leaves the counter exactly right")
    void multiSeatReservesAreAtomic() throws Exception {
        tierId = fixture.tier(eventId, "VIP", 7_500, 10);

        // Four seats each against ten available: two must win, and the leftover two
        // must NOT be handed out as a partial reservation.
        AtomicInteger reserved = countSuccessfulReserves(20, 4);

        assertThat(reserved.get()).isEqualTo(2);
        assertThat(fixture.remaining(tierId)).isEqualTo(2);
    }

    @Test
    @DisplayName("A tier whose counter is gone reports a fault, not a sold-out sale")
    void aMissingCounterIsNeverSoldOut() {
        fixture.loseStockCounter(eventId, tierId);

        assertThat(catalog.tryReserve(eventId, tierId, 1))
                .describedAs("INSUFFICIENT here would announce the end of a sale that is still open")
                .isEqualTo(ReserveResult.COUNTER_MISSING);
    }

    /**
     * Releases every caller at once, so they genuinely contend rather than queue.
     *
     * <p>Virtual threads, both because a thousand platform threads is a different test and because
     * it matches how the application actually serves these requests.
     */
    private AtomicInteger countSuccessfulReserves(int callers, int quantityEach) throws Exception {
        AtomicInteger reserved = new AtomicInteger();
        CountDownLatch startLine = new CountDownLatch(1);

        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var attempts = IntStream.range(0, callers)
                    .<Callable<Void>>mapToObj(i -> () -> {
                        startLine.await();
                        if (catalog.tryReserve(eventId, tierId, quantityEach)
                                == ReserveResult.RESERVED) {
                            reserved.incrementAndGet();
                        }
                        return null;
                    })
                    .toList();

            var futures = attempts.stream().map(pool::submit).toList();
            startLine.countDown();
            for (Future<Void> future : futures) {
                future.get(60, TimeUnit.SECONDS);
            }
        }
        return reserved;
    }
}

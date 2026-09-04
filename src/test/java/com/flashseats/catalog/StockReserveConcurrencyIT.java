package com.flashseats.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import com.flashseats.catalog.facade.CatalogFacade;
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
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The test the product exists to pass.
 *
 * <p>Every other guarantee is downstream of this one: if two buyers can both take the last seat,
 * nothing else matters. It runs against real PostgreSQL because the guarantee <em>is</em> a row lock
 * — an embedded database would let this pass while proving nothing.
 */
@DisplayName("Inventory cannot be oversold, however many buyers race for it")
class StockReserveConcurrencyIT extends IntegrationTest {

    @Autowired
    private CatalogFacade catalog;

    @Autowired
    private SaleFixture fixture;

    @Autowired
    private TransactionTemplate transactions;

    private long tierId;

    @BeforeEach
    void seedSale() {
        fixture.reset();
        long eventId = fixture.openEvent("Concurrency Test");
        tierId = fixture.tier(eventId, "General Admission", 2_500, 1);
    }

    @Test
    @DisplayName("50 threads race for the final seat: exactly one wins")
    void exactlyOneBuyerGetsTheLastSeat() throws Exception {
        int contenders = 50;
        AtomicInteger reserved = countSuccessfulReserves(contenders, 1);

        assertThat(reserved.get()).isEqualTo(1);
        assertThat(fixture.remaining(tierId)).isZero();
    }

    @Test
    @DisplayName("100 threads against 30 seats: exactly 30 sell, and not one more")
    void neverSellsMoreThanCapacity() throws Exception {
        long eventId = fixture.openEvent("Bulk Contention");
        tierId = fixture.tier(eventId, "Floor", 4_500, 30);

        AtomicInteger reserved = countSuccessfulReserves(100, 1);

        assertThat(reserved.get()).isEqualTo(30);
        assertThat(fixture.remaining(tierId)).isZero();
    }

    @Test
    @DisplayName("Partial demand leaves the counter exactly right")
    void multiSeatReservesAreAtomic() throws Exception {
        long eventId = fixture.openEvent("Multi-seat Contention");
        tierId = fixture.tier(eventId, "VIP", 7_500, 10);

        // Four seats each against ten available: two must win, and the leftover two
        // must NOT be handed out as a partial reservation.
        AtomicInteger reserved = countSuccessfulReserves(20, 4);

        assertThat(reserved.get()).isEqualTo(2);
        assertThat(fixture.remaining(tierId)).isEqualTo(2);
    }

    /** Releases every thread at once, so they genuinely contend rather than queue. */
    private AtomicInteger countSuccessfulReserves(int threads, int quantityEach) throws Exception {
        AtomicInteger reserved = new AtomicInteger();
        CountDownLatch startLine = new CountDownLatch(1);

        try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            var attempts = IntStream.range(0, threads)
                    .<Callable<Void>>mapToObj(i -> () -> {
                        startLine.await();
                        if (transactions.execute(tx -> catalog.tryReserve(tierId, quantityEach))) {
                            reserved.incrementAndGet();
                        }
                        return null;
                    })
                    .toList();

            var futures = attempts.stream().map(pool::submit).toList();
            startLine.countDown();
            for (Future<Void> future : futures) {
                future.get(30, TimeUnit.SECONDS);
            }
        }
        return reserved;
    }
}

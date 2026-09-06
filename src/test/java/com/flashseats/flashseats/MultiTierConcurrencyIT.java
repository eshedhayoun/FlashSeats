package com.flashseats.flashseats;
import static org.assertj.core.api.Assertions.assertThat;

import com.flashseats.catalog.facade.CatalogFacade;
import com.flashseats.flashseats.support.BuyerSession;
import com.flashseats.flashseats.support.IntegrationTest;
import com.flashseats.flashseats.support.SaleFixture;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.Duration;
class MultiTierConcurrencyIT extends IntegrationTest  {
    @Autowired
    private CatalogFacade catalog;

    @Autowired
    private SaleFixture fixture;

    @Autowired
    private TransactionTemplate transactions;

    private long eventId;
    private long vipTierId;
    private long generalTierId;
    private long balconyTierId;
    private static final Duration PATIENCE = Duration.ofSeconds(15);
    @LocalServerPort private int port;
    @Test
    @DisplayName("One buyer cant reserve multiple tiers concurrently")
    void oneBuyerCantReserveMultipleTiersConcurrently() throws Exception {
        
        BuyerSession buyer = admittedBuyer();
        CountDownLatch startLine = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newFixedThreadPool(3)){
            Future<Integer> vip = submitHold(pool,startLine,buyer,vipTierId,2);
            Future<Integer> general = submitHold(pool,startLine,buyer,generalTierId,3);
            Future<Integer> balcony = submitHold(pool,startLine,buyer,balconyTierId,4);
            startLine.countDown();

            int vipStatus = vip.get(30, TimeUnit.SECONDS);
            int generalStatus = general.get(30, TimeUnit.SECONDS);
            int balconyStatus = balcony.get(30, TimeUnit.SECONDS);
            List<Integer> statuses = List.of( vipStatus, generalStatus, balconyStatus );
            assertThat(count_success(statuses)) .as("Exactly one hold should be created") .isEqualTo(1);
            assertThat(count_rejected(statuses)) .as("The other two concurrent holds should be rejected") .isEqualTo(2);
            assertThat(statuses) .allMatch(status -> status == 201 || status == 409);
        }
        List<Integer> remaining = List.of( fixture.remaining(vipTierId), fixture.remaining(generalTierId), fixture.remaining(balconyTierId) );
        long changedTiers = remaining.stream().filter(value -> value != 10 && value != 20 && value != 30).count(); 
        assertThat(changedTiers).as("Only one tier should have a hold").isEqualTo(1);
        long vipSold = 10 - fixture.remaining(vipTierId);
        long generalSold = 20 - fixture.remaining(generalTierId); 
        long balconySold = 30 - fixture.remaining(balconyTierId); 
        long totalHeld = vipSold + generalSold + balconySold;
        assertThat(totalHeld).as("Only one hold should consume inventory").isIn(2L, 3L, 4L);
        assertThat(fixture.stockInvariantHolds(vipTierId)).isTrue(); 
        assertThat(fixture.stockInvariantHolds(generalTierId)).isTrue(); 
        assertThat(fixture.stockInvariantHolds(balconyTierId)).isTrue();
    }
    
    @BeforeEach
    void setUp() {
        fixture.reset();

        eventId = fixture.openEvent("Multi Tier Concurrency Test");

        vipTierId = fixture.tier(eventId,"VIP",10_000,10);

        generalTierId = fixture.tier(eventId,"General",5_000,20);

        balconyTierId = fixture.tier(eventId,"Balcony",2_500,30);
    }
    private long count_success(List<Integer> results) {
        return results.stream()
                .filter(status -> status == 201)
                .count();
    }
    private long count_rejected(List<Integer> results) {
        return results.stream()
                .filter(status -> status == 409)
                .count();
    }
    /* 
    //helpper methods that I might use in future tests
    private List<Callable<Boolean>> requests(long tierId,int quantity,int count) {
        List<Callable<Boolean>> tasks = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            tasks.add(() -> reserve(tierId, quantity));
        }
        return tasks;
    }
    private boolean reserve(long tierId, int quantity) {
        return transactions.execute(tx -> catalog.tryReserve(tierId, quantity));
    }*/
    
    private String admissionToken;
    private BuyerSession admittedBuyer(){
        BuyerSession buyer = new BuyerSession(port);
        var landing = buyer.get("/events/" + eventId); 
        assertThat(landing.ok()).isTrue();
        var join = buyer.post( "/queue/join", Map.of("eventId", eventId) );
        assertThat(join.status()).isEqualTo(202);
        String passToken = waitForPass(buyer);
        var admit = buyer.post( "/queue/admit", Map.of("eventId", eventId), Map.of("X-Queue-Pass-Token", passToken) );
        assertThat(admit.ok()).isTrue(); 
        admissionToken = admit.text("admissionToken"); 
        assertThat(admissionToken).isNotNull(); 
        return buyer;
    }
    private String waitForPass(BuyerSession buyer){
        long deadline = System.nanoTime() + PATIENCE.toNanos();
        while (System.nanoTime() < deadline) {
            var status = buyer.get("/queue/status?eventId=" + eventId);
            if (status.ok()) {
                String passToken = status.text("passToken");
                if (passToken != null) {
                    return passToken;
                }
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException( "Interrupted while waiting for queue promotion", e );
            }
        }
        throw new IllegalStateException( "Buyer was not promoted within " + PATIENCE );
    }
    private Future<Integer> submitHold(ExecutorService pool,CountDownLatch startLine,BuyerSession buyer,long tierId,int quantity) {
        return pool.submit(() -> {
                startLine.await();
                return buyer.post(
                        "/holds",
                        Map.of("eventId", eventId, "tierId", tierId,"quantity", quantity),
                        Map.of("X-Admission-Token",admissionToken)).status();
                });
    }
}
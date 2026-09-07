package com.flashseats.flashseats;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
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

import com.flashseats.catalog.facade.CatalogFacade;
import com.flashseats.flashseats.support.BuyerSession;
import com.flashseats.flashseats.support.IntegrationTest;
import com.flashseats.flashseats.support.SaleFixture;
import com.flashseats.hold.service.HoldService;
import static org.awaitility.Awaitility.await;
class MultiTierConcurrencyIT extends IntegrationTest  {
    @Autowired
    private CatalogFacade catalog;

    @Autowired
    private SaleFixture fixture;

    @Autowired
    private TransactionTemplate transactions;

    @Autowired 
    private HoldService holdService;
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
    @Test
    @DisplayName("Checkout and hold expiry racing each other never creates an inconsistent order")
    void checkoutAndHoldExpiryRacingEachOtherNeverCreatesInconsistentOrder() throws Exception {
        BuyerSession buyer = admittedBuyer();
        String holdToken = reserve(buyer, vipTierId, 1);
        assertThat(fixture.remaining(vipTierId)).isEqualTo(9);
        CountDownLatch startLine = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newFixedThreadPool(2)){
            Future<Integer> checkout = submitCheckout( pool, startLine, buyer, holdToken );
            Future<Integer> expiry = pool.submit(() -> { 
                startLine.await(); 
                return holdService.sweepExpired(); 
            });
            startLine.countDown();
            int checkoutStatus = checkout.get(30, TimeUnit.SECONDS); 
            int reclaimed = expiry.get(30, TimeUnit.SECONDS);
            assertThat(checkoutStatus).isIn(201, 404, 409, 410);
            assertThat(reclaimed).isIn(0, 1);
        }
        String status = fixture.holdStatus(holdToken);
        switch (status) {
            case "CONSUMED":
                assertThat(fixture.countOrders()).isEqualTo(1);
                assertThat(fixture.countPaymentTransactions()).isEqualTo(1);
                assertThat(fixture.remaining(vipTierId)).isEqualTo(9);
                break;
            case "EXPIRED":
                assertThat(fixture.countOrders()).isEqualTo(0);
                assertThat(fixture.countPaymentTransactions()).isEqualTo(0);
                assertThat(fixture.remaining(vipTierId)).isEqualTo(10);
            default:
                throw new AssertionError( "Unexpected hold status: " + status );
                
        }
        assertThat(fixture.stockInvariantHolds(vipTierId)).isTrue();
    }
    @Test 
    @DisplayName("An expired hold cannot be checked out") 
    void expiredHoldCannotBeCheckedOut(){
        BuyerSession buyer = admittedBuyer();
        String holdToken = reserve(buyer, vipTierId, 2);
        assertThat(fixture.remaining(vipTierId)).isEqualTo(8);
        fixture.expireHold(holdToken);
        await().atMost(PATIENCE) .untilAsserted(() -> { 
            assertThat(fixture.holdStatus(holdToken)).isEqualTo("EXPIRED");
            assertThat(fixture.remaining(vipTierId)).isEqualTo(10);
         });
        int checkoutStatus = buyer.post(
                "/orders/checkout",
                checkout(holdToken, "pm_card_visa")
        ).status();
        assertThat(checkoutStatus).isIn(404, 409, 410);
        assertThat(fixture.countOrders()).isEqualTo(0);
        assertThat(fixture.countPaymentTransactions()).isEqualTo(0);
        assertThat(fixture.remaining(vipTierId)).isEqualTo(10);
        assertThat(fixture.holdStatus(holdToken)) .isEqualTo("EXPIRED");
        assertThat(fixture.stockInvariantHolds(vipTierId)).isTrue();
    }
    //helpper methods that I might use in future tests

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
    
    private String reserve(BuyerSession buyer, long tierId, int quantity) {
        return buyer.post(
                "/holds",
                Map.of(
                        "eventId", eventId,
                        "tierId", tierId,
                        "quantity", quantity),
                        Map.of("X-Admission-Token", admissionToken)).text("holdToken");
    }
    
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
    private Map<String, Object> checkout(String holdToken,String paymentMethodId) {
        return Map.of(
                "holdToken", holdToken,
                "userEmail", "buyer@example.com",
                "paymentMethodId", paymentMethodId,
                "idempotencyKey", "test-" + holdToken
        );
    }
    private Future<Integer> submitCheckout(
        ExecutorService pool,
        CountDownLatch startLine,
        BuyerSession buyer,
        String holdToken) {

        return pool.submit(() -> {
            startLine.await();

            return buyer.post(
                    "/orders/checkout",
                    checkout(holdToken, "pm_card_visa")
            ).status();
        });
    }
    private boolean isSuccess(int status) { 
        return status == 200 || status == 201; 
    }
}
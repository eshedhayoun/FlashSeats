package com.flashseats.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.flashseats.app.support.BuyerSession;
import com.flashseats.app.support.IntegrationTest;
import com.flashseats.app.support.SaleFixture;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * A request records the one observation somebody reads, and not the ones nobody does (ADR-079).
 *
 * <p>Spring Security observes every filter in its chain and Lettuce observes every Redis command,
 * both on by default and both on the hottest path. Under load they were most of a status request's
 * metrics work and over a third of the Lettuce I/O thread. Nothing alarms on them and no drill reads
 * them; {@code http.server.requests} is what every drill reads, so it must survive the cut.
 */
@DisplayName("Requests record http.server.requests, and neither Spring Security's nor Lettuce's observations")
class RequestObservationsIT extends IntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private SaleFixture fixture;

    @Autowired
    private MeterRegistry meters;

    @Test
    void onlyTheReadObservationsAreRecorded() {
        fixture.reset();
        long eventId = fixture.openEvent("Observed");
        fixture.tierWithoutCounter(eventId, "General Admission", 2_500, 100);

        BuyerSession buyer = new BuyerSession(port);
        buyer.get("/events/" + eventId);
        buyer.post("/queue/join", Map.of("eventId", eventId));
        assertThat(buyer.get("/queue/status?eventId=" + eventId).status()).isEqualTo(200);

        assertThat(meters.find("http.server.requests").tag("uri", "/api/v1/queue/status").timer())
                .describedAs("the latency every drill reads")
                .isNotNull();
        assertThat(meters.find("spring.security.filterchains").meters()).isEmpty();
        assertThat(meters.find("spring.security.authorizations").meters()).isEmpty();
        assertThat(meters.find("lettuce").meters()).isEmpty();
    }
}

package com.flashseats.notification;

import static org.assertj.core.api.Assertions.assertThat;

import com.flashseats.flashseats.support.IntegrationTest;
import com.flashseats.flashseats.support.SaleFixture;
import com.flashseats.notification.model.NotificationKind;
import com.flashseats.notification.service.NotificationLogService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The delivery claim: strong enough to stop a double send, weak enough to allow a replay.
 *
 * <p>Insert-then-send is the right shape — the unique violation is atomic where a preceding
 * {@code SELECT} is a race — but the claim was also permanent. A transient SMTP outage dead-lettered
 * the message <em>and</em> kept the claim, so replaying it from the DLQ found the row already there
 * and acknowledged without sending. The ticket was gone with no automated recovery (ADR-038).
 */
@DisplayName("A notification claim blocks a duplicate but releases a dead letter")
class NotificationClaimIT extends IntegrationTest {

    private static final NotificationKind KIND = NotificationKind.TICKET_DELIVERY;
    private static final String ORDER = "TK-00042";
    private static final String EMAIL = "buyer@example.com";

    @Autowired
    private NotificationLogService logs;

    @Autowired
    private SaleFixture fixture;

    @BeforeEach
    void reset() {
        fixture.reset();
    }

    @Test
    @DisplayName("The first caller wins and the second is turned away")
    void claimIsExclusive() {
        assertThat(logs.claim(ORDER, KIND, EMAIL)).isTrue();
        assertThat(logs.claim(ORDER, KIND, EMAIL)).isFalse();
    }

    @Test
    @DisplayName("A sent message can never be claimed again")
    void sentIsTerminal() {
        assertThat(logs.claim(ORDER, KIND, EMAIL)).isTrue();
        logs.markSent(ORDER, KIND);

        // The whole point of the guard: no buyer receives two tickets, whatever the broker redelivers.
        assertThat(logs.claim(ORDER, KIND, EMAIL)).isFalse();
    }

    @Test
    @DisplayName("A dead-lettered message can be replayed")
    void deadLetteredIsReclaimable() {
        assertThat(logs.claim(ORDER, KIND, EMAIL)).isTrue();
        logs.markDeadLettered(ORDER, KIND, "smtp connect timeout");

        // Nothing was delivered, so the claim must not still be held. Otherwise a DLQ replay is a
        // silent no-op and the only recovery is deleting a row by hand.
        assertThat(logs.claim(ORDER, KIND, EMAIL)).isTrue();

        // ...and once the replay succeeds it is terminal again.
        logs.markSent(ORDER, KIND);
        assertThat(logs.claim(ORDER, KIND, EMAIL)).isFalse();
    }

    @Test
    @DisplayName("Each kind is claimed independently")
    void kindsDoNotCollide() {
        assertThat(logs.claim(ORDER, NotificationKind.TICKET_DELIVERY, EMAIL)).isTrue();
        // The same order still owes a refund notice; UNIQUE(order_number, kind) is what allows both.
        assertThat(logs.claim(ORDER, NotificationKind.REFUND_NOTICE, EMAIL)).isTrue();
    }
}

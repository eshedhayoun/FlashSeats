package com.flashseats.hold.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.flashseats.catalog.facade.CatalogFacade;
import com.flashseats.catalog.facade.EventWindowStatus;
import com.flashseats.catalog.facade.ReserveResult;
import com.flashseats.catalog.facade.TierSummary;
import com.flashseats.hold.config.HoldProperties;
import com.flashseats.hold.repository.TicketHoldRepository;
import com.flashseats.queue.facade.QueueFacade;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.orm.jpa.JpaSystemException;
import org.springframework.transaction.CannotCreateTransactionException;

/**
 * The reserve runs before the hold's row is written, so every failure after it is a question: did a
 * row commit or not? Only a certain "no" may give the seats back (ADR-046, ADR-067).
 */
@DisplayName("A reservation gives its seats back exactly when no hold can exist")
class HoldCreationCompensationTest {

    private static final Instant NOW = Instant.parse("2026-10-02T12:00:00Z");

    private final TicketHoldRepository holds = mock(TicketHoldRepository.class);
    private final CatalogFacade catalog = mock(CatalogFacade.class);
    private final QueueFacade queue = mock(QueueFacade.class);

    private final HoldService service = new HoldService(
            holds,
            catalog,
            queue,
            new HoldProperties(),
            mock(ApplicationEventPublisher.class),
            mock(HoldTimers.class),
            Clock.fixed(NOW, ZoneOffset.UTC));

    @BeforeEach
    void anAdmittedBuyerAndAnOpenSale() {
        when(queue.verifyAdmission("adm", "sid", 1L)).thenReturn(true);
        when(catalog.getTierSummary(1L, 2L)).thenReturn(new TierSummary(
                1L, 2L, "Floor", 5_000, "USD", 6, "Fest", "Hall", NOW.plusSeconds(86_400),
                NOW.plusSeconds(3_600), EventWindowStatus.OPEN));
        when(catalog.tryReserve(1L, 2L, 2)).thenReturn(ReserveResult.RESERVED);
    }

    @Test
    @DisplayName("No connection came free, so the transaction never began: the seats go back")
    void aTransactionThatNeverBeganIsCompensated() {
        when(holds.saveAndFlush(any())).thenThrow(new CannotCreateTransactionException("pool exhausted"));

        assertThatThrownBy(() -> service.createHold("sid", 1L, 2L, 2, "adm"))
                .isInstanceOf(CannotCreateTransactionException.class);

        verify(catalog).restore(1L, 2L, 2);
    }

    @Test
    @DisplayName("A failure that may have committed leaves the seats where they are, for drift and a rebuild")
    void anAmbiguousFailureIsNotCompensated() {
        when(holds.saveAndFlush(any())).thenThrow(new JpaSystemException(new RuntimeException("connection reset")));

        assertThatThrownBy(() -> service.createHold("sid", 1L, 2L, 2, "adm"))
                .isInstanceOf(JpaSystemException.class);

        verify(catalog, never()).restore(anyLong(), anyLong(), anyInt());
    }
}

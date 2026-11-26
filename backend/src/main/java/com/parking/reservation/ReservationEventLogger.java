package com.parking.reservation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/** Phase 1 handler: log only. Never fires for a rolled-back reservation. */
@Component
public class ReservationEventLogger {

    private static final Logger log = LoggerFactory.getLogger(ReservationEventLogger.class);

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onConfirmed(ReservationConfirmed event) {
        log.info("Reservation confirmed: reservationId={}, spaceId={}, date={}, userId={}",
                event.reservationId(), event.spaceId(), event.date(), event.userId());
    }
}

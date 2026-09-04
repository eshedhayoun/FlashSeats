package com.flashseats.queue.facade;

import com.flashseats.queue.service.QueueService;
import org.springframework.stereotype.Component;

/** Thin delegation to {@link QueueService}. Package-private. */
@Component
class QueueFacadeImpl implements QueueFacade {

    private final QueueService queue;

    QueueFacadeImpl(QueueService queue) {
        this.queue = queue;
    }

    @Override
    public boolean verifyAdmission(String admissionToken, String userSessionId, long eventId) {
        return admissionToken != null && queue.hasLiveAdmission(admissionToken, userSessionId, eventId);
    }

    @Override
    public void revokeAdmission(String userSessionId, long eventId) {
        queue.revokeAdmission(userSessionId, eventId);
    }

    @Override
    public QueueState getQueueState(String userSessionId, long eventId) {
        return queue.getQueueState(userSessionId, eventId);
    }
}

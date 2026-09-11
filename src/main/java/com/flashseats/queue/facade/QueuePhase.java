package com.flashseats.queue.facade;

/**
 * Where a session stands in the sale. Drives the client's router directly — the SPA renders the view
 * the server says it is in, rather than deriving one from navigation history.
 */
public enum QueuePhase {
    /** Has not joined. */
    NOT_JOINED,
    /** In line. */
    WAITING,
    /** Promoted, holding an unspent pass. The client should exchange it immediately. */
    PROMOTED,
    /** Inside the sale, free to browse tiers. */
    ADMITTED,
    /** Stock is gone and the queue has drained. */
    EXHAUSTED,
    /** The sale window closed. */
    CLOSED
}

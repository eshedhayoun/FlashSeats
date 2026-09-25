package com.flashseats.bot.facade;

/**
 * The one way into {@code bot} that is not a servlet filter.
 *
 * <p>Called by {@code queue} on join — the single moment in the journey where a challenge is worth
 * its cost. Join is where an automated buyer gains its advantage: it is the front of the line, it is
 * cheap to repeat, and a session id costs nothing to mint. Everything after it is already gated by a
 * queue pass and an admission the server issued.
 */
public interface BotFacade {

    /**
     * Checks a visitor's challenge token before they take a place in the line.
     *
     * <p><strong>Fails open</strong> (ADR-011). A provider that times out, errors, or is not
     * configured lets the visitor through; only a token the provider actively scores below the
     * threshold is refused. A challenge provider's outage must not close a sale.
     *
     * @throws com.flashseats.shared.error.FlashSeatsException {@code BOT_VERIFICATION_FAILED} only
     *     when the provider returned a score below {@code flashseats.bot.recaptcha.min-score}
     */
    void verifyHuman(String sessionId, String recaptchaToken, String ipAddress);
}

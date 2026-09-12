package com.flashseats.notification.service;

import com.flashseats.notification.dto.OrderConfirmedPayload;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import org.springframework.stereotype.Component;
import org.springframework.web.util.HtmlUtils;

/**
 * Builds the HTML email body.
 *
 * <p>Composed in Java rather than Thymeleaf on purpose. The body is one small, fixed layout; a
 * template engine would add a dependency on file resolution, a character-set configuration and a
 * class of deterministic render failure that dead-letters mail — for no gain at this size. Swapping
 * in Thymeleaf later means replacing this one class.
 *
 * <p>Every interpolated value is HTML-escaped: an event title is operator-supplied text, and an
 * email client is a rendering engine.
 */
@Component
public class EmailComposer {

    private static final DateTimeFormatter DATE =
            DateTimeFormatter.ofPattern("EEEE d MMMM yyyy 'at' HH:mm z").withZone(ZoneOffset.UTC);

    public String subjectFor(OrderConfirmedPayload payload) {
        return "Your tickets for " + payload.event().title() + " (" + payload.orderNumber() + ")";
    }

    // ------------------------------------------------------------ refund notice

    public String refundSubjectFor(OrderConfirmedPayload payload) {
        return "Refund issued for " + payload.orderNumber();
    }

    /**
     * The refund notice.
     *
     * <p><strong>It must not dereference {@code event} or {@code items}.</strong> The refund payload
     * is written by {@code markRefunded}, which carries a null {@code event} and an empty item list —
     * there were no seats to describe, which is the entire reason a refund happened. A composer that
     * assumed the confirmation shape would throw here, and a deterministic render failure on the
     * refund path dead-letters the one message telling a buyer their money is coming back.
     *
     * <p>The tone is deliberate. This email reaches someone who was charged and did not get what
     * they paid for, so it leads with the refund, names the amount, and does not open with an
     * apology that buries the fact.
     */
    public String refundBodyFor(OrderConfirmedPayload payload) {
        return """
                <!doctype html>
                <html><body style="font-family:system-ui,-apple-system,'Segoe UI',sans-serif;
                                   color:#1b1e1c; max-width:560px; margin:0 auto; padding:24px;">
                  <h1 style="font-size:20px; margin:0 0 4px;">We've refunded you in full.</h1>
                  <p style="margin:0 0 20px; color:#4c534e;">
                    Your payment of <strong>%s</strong> has been refunded. It usually reaches your
                    account within a few working days, depending on your bank.
                  </p>

                  <p style="margin:0 0 20px; color:#4c534e;">
                    We took the payment but could not complete the booking, so the seats were not
                    held and you have not been charged for them. <strong>You do not need to do
                    anything.</strong>
                  </p>

                  <p style="margin:24px 0 0; font-size:13px; color:#79817b;">
                    Order reference <strong>%s</strong>
                  </p>
                </body></html>
                """
                .formatted(
                        money(payload.totalAmountCents(), payload.currency()),
                        escape(payload.orderNumber()));
    }

    public String bodyFor(OrderConfirmedPayload payload) {
        StringBuilder lines = new StringBuilder();
        for (OrderConfirmedPayload.Item item : payload.items()) {
            lines.append(
                    """
                    <tr>
                      <td style="padding:6px 0;">%s &times; %d</td>
                      <td style="padding:6px 0; text-align:right;">%s</td>
                    </tr>
                    """
                            .formatted(
                                    escape(item.tierName()),
                                    item.quantity(),
                                    money(item.unitPriceCents() * item.quantity(), payload.currency())));
        }

        return """
                <!doctype html>
                <html><body style="font-family:system-ui,-apple-system,'Segoe UI',sans-serif;
                                   color:#1b1e1c; max-width:560px; margin:0 auto; padding:24px;">
                  <h1 style="font-size:20px; margin:0 0 4px;">You're going.</h1>
                  <p style="margin:0 0 24px; color:#4c534e;">
                    Your tickets are attached as a PDF. Show them at the door.
                  </p>

                  <h2 style="font-size:17px; margin:0 0 2px;">%s</h2>
                  <p style="margin:0 0 20px; color:#4c534e;">%s<br>%s</p>

                  <table style="width:100%%; border-collapse:collapse; font-size:14px;">
                    %s
                    <tr style="border-top:1px solid #dbd8d0; font-weight:600;">
                      <td style="padding:10px 0;">Total</td>
                      <td style="padding:10px 0; text-align:right;">%s</td>
                    </tr>
                  </table>

                  <p style="margin:24px 0 0; font-size:13px; color:#79817b;">
                    Order reference <strong>%s</strong>
                  </p>
                </body></html>
                """
                .formatted(
                        escape(payload.event().title()),
                        escape(payload.event().venueName()),
                        DATE.format(payload.event().startTime()),
                        lines,
                        money(payload.totalAmountCents(), payload.currency()),
                        escape(payload.orderNumber()));
    }

    private String money(long cents, String currency) {
        return "%s %d.%02d".formatted(currency, cents / 100, Math.abs(cents % 100));
    }

    private String escape(String value) {
        return value == null ? "" : HtmlUtils.htmlEscape(value);
    }
}

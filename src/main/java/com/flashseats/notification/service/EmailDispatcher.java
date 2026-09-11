package com.flashseats.notification.service;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

/**
 * Sends the message over SMTP — Mailpit locally, a real relay elsewhere.
 *
 * <p>Called with <strong>no transaction open</strong>. SMTP is a network call and this is the
 * slowest step in fulfilment; holding a database connection across it would put checkout behind the
 * mail server (ADR-023).
 */
@Component
@ConditionalOnProperty(
        name = "flashseats.notification.enabled", havingValue = "true", matchIfMissing = true)
public class EmailDispatcher {

    private final JavaMailSender mailSender;
    private final String fromAddress;

    public EmailDispatcher(
            JavaMailSender mailSender, @Value("${flashseats.mail.from}") String fromAddress) {
        this.mailSender = mailSender;
        this.fromAddress = fromAddress;
    }

    public void send(String to, String subject, String htmlBody, byte[] pdf, String pdfName)
            throws MessagingException {

        MimeMessage message = mailSender.createMimeMessage();
        MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");

        helper.setFrom(fromAddress);
        helper.setTo(to);
        helper.setSubject(subject);
        helper.setText(htmlBody, true);
        helper.addAttachment(pdfName, new ByteArrayResource(pdf), "application/pdf");

        mailSender.send(message);
    }
}

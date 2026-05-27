import jakarta.mail.*;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;

import java.util.Date;
import java.util.Properties;

/**
 * SMTP send.
 *
 * Two entry points share the same underlying delivery:
 *   - {@link #reply} threads a reply onto an inbound message (used by the
 *     `reply` action and by the `github.issue.create.result` follow-up).
 *   - {@link #send} sends to an arbitrary address. Used by the
 *     `mail.send` bus handler; callers are responsible for enforcing
 *     the recipient allow-list before calling this.
 */
public class EmailSender {

    private final Config config;
    private final Session session;

    public EmailSender(Config config) {
        this.config = config;
        Properties props = new Properties();
        props.put("mail.smtp.auth", "true");
        props.put("mail.smtp.host", config.smtpHost);
        props.put("mail.smtp.port", String.valueOf(config.smtpPort));
        props.put("mail.smtp.ssl.enable", "true");      // 465 → implicit TLS
        this.session = Session.getInstance(props, new Authenticator() {
            @Override protected PasswordAuthentication getPasswordAuthentication() {
                return new PasswordAuthentication(config.smtpUser, config.smtpPassword);
            }
        });
    }

    /** Send a reply to an inbound message, preserving threading headers. Returns the SMTP Message-Id of the sent mail. */
    public String reply(MailFetcher.InboundMail inbound, String body) throws MessagingException {
        String subj = inbound.subject() == null ? "" : inbound.subject();
        String replySubj = subj.regionMatches(true, 0, "Re:", 0, 3) ? subj : "Re: " + subj;
        return send(inbound.from(), replySubj, body, inbound.messageId());
    }

    /**
     * Send to an arbitrary recipient.
     *
     * Callers MUST gate this on a recipient allow-list — this method does
     * not check {@link Config#allowedRecipients}. The check belongs in the
     * handler that invokes send(), so that the rejection can surface as a
     * hard failure (dead-letter) rather than an SMTP attempt.
     *
     * @param inReplyTo  Message-Id of a thread to reply into, or null.
     * @return the SMTP Message-Id assigned to the sent mail.
     */
    public String send(String to, String subject, String body, String inReplyTo) throws MessagingException {
        MimeMessage out = new MimeMessage(session);
        try {
            // TODO: make display name configurable; for now derive from elfName.
            out.setFrom(new InternetAddress(config.smtpUser, config.elfName));
        } catch (java.io.UnsupportedEncodingException e) {
            out.setFrom(new InternetAddress(config.smtpUser));
        }
        out.setRecipients(Message.RecipientType.TO, new Address[]{ new InternetAddress(to) });
        out.setSubject(subject);

        if (inReplyTo != null && !inReplyTo.isEmpty()) {
            out.setHeader("In-Reply-To", inReplyTo);
            out.setHeader("References", inReplyTo);
        }
        out.setSentDate(new Date());
        out.setText(body, "UTF-8");

        Transport.send(out);
        return out.getMessageID();
    }
}

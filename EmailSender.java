import jakarta.mail.*;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;

import java.util.Date;
import java.util.Properties;

/**
 * SMTP send for the `reply` action. Implements RFC-compliant threading
 * via In-Reply-To / References so the operator's mail client groups our
 * replies under the original thread.
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

    /** Send a reply to an inbound message, preserving threading headers. */
    public void reply(MailFetcher.InboundMail inbound, String body) throws MessagingException {
        MimeMessage out = new MimeMessage(session);
        try {
            // TODO: make display name configurable; for now derive from elfName.
            out.setFrom(new InternetAddress(config.smtpUser, config.elfName));
        } catch (java.io.UnsupportedEncodingException e) {
            out.setFrom(new InternetAddress(config.smtpUser));
        }
        out.setRecipients(Message.RecipientType.TO,
                new Address[]{ new InternetAddress(inbound.from()) });

        String subj = inbound.subject() == null ? "" : inbound.subject();
        out.setSubject(subj.regionMatches(true, 0, "Re:", 0, 3) ? subj : "Re: " + subj);

        if (inbound.messageId() != null && !inbound.messageId().isEmpty()) {
            out.setHeader("In-Reply-To", inbound.messageId());
            out.setHeader("References", inbound.messageId());
        }
        out.setSentDate(new Date());
        out.setText(body, "UTF-8");

        Transport.send(out);
    }
}

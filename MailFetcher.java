import jakarta.mail.*;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMultipart;
import jakarta.mail.search.FlagTerm;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * IMAP fetcher with allow-list gating. Pulls UNSEEN messages from INBOX,
 * filters by From: against config.allowedSenders, returns lightweight records.
 *
 * Messages whose sender is NOT allow-listed are left UNSEEN — operator can
 * triage them by hand in their normal mail client.
 */
public class MailFetcher {

    public record InboundMail(
            String messageId,
            String from,
            String subject,
            String date,
            String inReplyTo,
            String body
    ) {}

    private final Config config;

    public MailFetcher(Config config) { this.config = config; }

    public List<InboundMail> fetchUnseenAllowListed() {
        List<InboundMail> out = new ArrayList<>();

        Properties props = new Properties();
        props.put("mail.store.protocol", "imaps");
        Session session = Session.getInstance(props);

        try (Store store = session.getStore("imaps")) {
            store.connect(config.imapHost, config.imapPort, config.imapUser, config.imapPassword);
            try (Folder inbox = store.getFolder("INBOX")) {
                inbox.open(Folder.READ_WRITE);
                Message[] unseen = inbox.search(new FlagTerm(new Flags(Flags.Flag.SEEN), false));

                for (Message m : unseen) {
                    String fromAddr = extractFrom(m);
                    if (fromAddr == null || !config.allowedSenders.contains(fromAddr.toLowerCase())) {
                        // Not allow-listed: leave UNSEEN, don't peek body.
                        continue;
                    }
                    out.add(new InboundMail(
                            firstHeader(m, "Message-Id"),
                            fromAddr,
                            nullSafe(m.getSubject()),
                            firstHeader(m, "Date"),
                            firstHeader(m, "In-Reply-To"),
                            extractTextBody(m)
                    ));
                    // Mark seen only after successful read. If later processing fails,
                    // IdempotencyStore (keyed on Message-Id) prevents double-processing.
                    m.setFlag(Flags.Flag.SEEN, true);
                }
            }
        } catch (MessagingException | IOException e) {
            throw new RuntimeException("IMAP fetch failed: " + e.getMessage(), e);
        }
        return out;
    }

    private static String extractFrom(Message m) throws MessagingException {
        Address[] from = m.getFrom();
        if (from == null || from.length == 0) return null;
        if (from[0] instanceof InternetAddress ia) return ia.getAddress();
        return from[0].toString();
    }

    private static String firstHeader(Message m, String name) throws MessagingException {
        String[] h = m.getHeader(name);
        return (h == null || h.length == 0) ? "" : h[0];
    }

    private static String nullSafe(String s) { return s == null ? "" : s; }

    /**
     * Extract the first text/plain part. HTML-only messages return empty —
     * the triage agent will then most likely emit an `ignore` action,
     * which is the right default for noise.
     */
    private static String extractTextBody(Message m) throws MessagingException, IOException {
        Object content = m.getContent();
        if (content instanceof String s) return s;
        if (content instanceof MimeMultipart mp) return walkMultipart(mp);
        return "";
    }

    private static String walkMultipart(MimeMultipart mp) throws MessagingException, IOException {
        for (int i = 0; i < mp.getCount(); i++) {
            BodyPart part = mp.getBodyPart(i);
            if (part.isMimeType("text/plain")) return (String) part.getContent();
            if (part.getContent() instanceof MimeMultipart nested) {
                String s = walkMultipart(nested);
                if (!s.isEmpty()) return s;
            }
        }
        return "";
    }
}

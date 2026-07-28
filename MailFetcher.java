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
            String body,
            String references,
            String authResults,
            List<String> attachments
    ) {
        /** Convenience for legacy call sites that carry no session fields. */
        public InboundMail(String messageId, String from, String subject,
                String date, String inReplyTo, String body) {
            this(messageId, from, subject, date, inReplyTo, body, "", "", List.of());
        }

        /**
         * The stable key for the mail thread: the root Message-Id from
         * References (first entry), else this message's own id. Replies in
         * the same thread share it, which is what lets session-worker map a
         * thread to one continuing Claude session.
         */
        public String threadKey() {
            String refs = references == null ? "" : references.trim();
            if (!refs.isEmpty()) {
                int end = refs.indexOf('>');
                if (refs.startsWith("<") && end > 0) return refs.substring(0, end + 1);
            }
            return messageId;
        }

        /** Whether Gmail authenticated the sender's DKIM signature. */
        public boolean dkimPass() {
            return authResults != null && authResults.toLowerCase().contains("dkim=pass");
        }
    }

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
                    String messageId = firstHeader(m, "Message-Id");
                    out.add(new InboundMail(
                            messageId,
                            fromAddr,
                            nullSafe(m.getSubject()),
                            firstHeader(m, "Date"),
                            firstHeader(m, "In-Reply-To"),
                            extractTextBody(m),
                            firstHeader(m, "References"),
                            allHeaders(m, "Authentication-Results"),
                            saveAttachments(m, messageId)
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

    /** All values of a header joined - Authentication-Results may repeat. */
    private static String allHeaders(Message m, String name) throws MessagingException {
        String[] h = m.getHeader(name);
        return h == null ? "" : String.join("\n", h);
    }

    /**
     * Spool every non-text attachment to local disk so downstream elves (a
     * Claude session, notably) can read them - IMAP carries full MIME, which
     * is exactly what hosted-connector integrations cannot give us.
     */
    private List<String> saveAttachments(Message m, String messageId)
            throws MessagingException, IOException {
        Object content = m.getContent();
        if (!(content instanceof MimeMultipart mp)) return List.of();
        List<String> saved = new ArrayList<>();
        java.nio.file.Path dir = config.attachmentsDir().resolve(safeName(messageId));
        collectAttachments(mp, dir, saved);
        return List.copyOf(saved);
    }

    private static void collectAttachments(MimeMultipart mp, java.nio.file.Path dir,
            List<String> saved) throws MessagingException, IOException {
        for (int i = 0; i < mp.getCount(); i++) {
            BodyPart part = mp.getBodyPart(i);
            if (part.getContent() instanceof MimeMultipart nested) {
                collectAttachments(nested, dir, saved);
                continue;
            }
            String fileName = part.getFileName();
            boolean isAttachment = Part.ATTACHMENT.equalsIgnoreCase(part.getDisposition())
                    || (fileName != null && !part.isMimeType("text/*"));
            if (!isAttachment || fileName == null) continue;
            java.nio.file.Files.createDirectories(dir);
            java.nio.file.Path target = dir.resolve(safeName(fileName));
            try (var in = part.getInputStream()) {
                java.nio.file.Files.copy(in, target,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            saved.add(target.toAbsolutePath().toString());
        }
    }

    private static String safeName(String raw) {
        String cleaned = raw == null ? "unnamed" : raw.replaceAll("[^A-Za-z0-9._-]", "_");
        return cleaned.length() > 120 ? cleaned.substring(0, 120) : cleaned;
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

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.mail.MessagingException;

import java.io.IOException;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Handlers for elf-bus envelopes arriving in mail-worker's own inbox.
 *
 * Two flavours:
 *   - Result handlers (e.g. {@link IssueCreated}) — handle terminal *.result
 *     kinds. They produce SMTP follow-ups but no further bus messages.
 *   - Request handlers (e.g. {@link MailSend}) — handle inbound requests
 *     from peer elves. They perform an effect and emit a *.result reply
 *     for callers that set X-Elf-Reply-To.
 */
public final class BusHandlers {

    private BusHandlers() {}

    /**
     * github.issue.create.result
     *
     * Body schema:
     *   { "status": "ok",
     *     "issue":  { "repo": "...", "number": 42, "url": "https://..." } }
     */
    public static final class IssueCreated implements ElfBusHandler {
        private final EmailSender sender;
        private final PendingStore pending;
        private final Config config;

        public IssueCreated(EmailSender sender, PendingStore pending, Config config) {
            this.sender = sender;
            this.pending = pending;
            this.config = config;
        }

        @Override public String kind() { return "github.issue.create.result"; }

        @Override
        public Map<String, Object> execute(ElfBusEnvelope env) throws Exception {
            String correlationId = env.correlationId();
            if (correlationId == null || correlationId.isBlank()) {
                throw new IllegalStateException("result without correlation id");
            }
            Optional<PendingStore.PendingReply> maybe = pending.take(correlationId);
            if (maybe.isEmpty()) {
                // No pending entry. Two likely causes:
                //   - mail-worker restarted and lost state → operator triages by hand.
                //   - duplicate result (idempotency already deleted the entry) → safe to drop.
                // Either way, do nothing and let the bus consumer record this as seen.
                System.out.println("result: no pending entry for " + correlationId + "; dropping");
                return null;
            }
            PendingStore.PendingReply pr = maybe.get();

            JsonNode b = env.body();
            String status = b.path("status").asText("ok");
            if (!"ok".equals(status)) {
                // The remote elf reported a failure. Forward it to the principal.
                String detail = b.path("error").asText("(no detail)");
                sendReply(pr,
                        "Filing the issue did not succeed — " + detail + ".\n\n"
                                + "— " + config.elfName);
                return null;
            }

            JsonNode issue = b.path("issue");
            String url = issue.path("url").asText("");
            int number  = issue.path("number").asInt(0);
            String repo = issue.path("repo").asText("");

            String body = """
                    Filed as %s#%d.

                    %s

                    — %s
                    """.formatted(repo, number, url, config.elfName);
            sendReply(pr, body);
            return null;
        }

        private void sendReply(PendingStore.PendingReply pr, String body) throws MessagingException {
            // Construct a synthetic InboundMail just to drive EmailSender.reply().
            // EmailSender only reads {from, subject, messageId} — body is unused.
            var fake = new MailFetcher.InboundMail(
                    pr.inboundMessageId(),
                    pr.fromAddress(),
                    pr.subject(),
                    "", "", ""
            );
            sender.reply(fake, body);
        }
    }

    /**
     * mail.send → mail.send.result
     *
     * Payload schema:
     *   { "to": "<address>",
     *     "subject": "...",
     *     "body": "...",
     *     "in_reply_to": "<message-id>"  // optional
     *   }
     *
     * Result payload:
     *   { "status": "ok", "message_id": "<smtp-id>" }   on success
     *   (hard failures dead-letter; we don't emit error results in v1)
     *
     * Recipient gating: {@code to} MUST appear in
     * {@link Config#allowedRecipients()} (case-insensitive). Forbidden
     * recipients raise IllegalArgumentException, which the consumer
     * dead-letters (not retryable — config won't change between attempts).
     */
    /**
     * session-worker finished (or failed) a mail-driven Claude session:
     * forward the outcome to the principal as a threaded reply, so replying
     * to THAT mail continues the same session.
     */
    public static final class SessionResult implements ElfBusHandler {
        private final EmailSender sender;
        private final PendingStore pending;
        private final Config config;

        public SessionResult(EmailSender sender, PendingStore pending, Config config) {
            this.sender = sender;
            this.pending = pending;
            this.config = config;
        }

        @Override public String kind() { return "session.run.result"; }

        @Override
        public Map<String, Object> execute(ElfBusEnvelope env) throws Exception {
            String correlationId = env.correlationId();
            if (correlationId == null || correlationId.isBlank()) {
                throw new IllegalStateException("result without correlation id");
            }
            Optional<PendingStore.PendingReply> maybe = pending.take(correlationId);
            if (maybe.isEmpty()) {
                System.out.println("session result: no pending entry for " + correlationId + "; dropping");
                return null;
            }
            PendingStore.PendingReply pr = maybe.get();

            JsonNode b = env.body();
            String status = b.path("status").asText("ok");
            String body;
            if (!"ok".equals(status)) {
                body = "The session hit a problem - " + b.path("error").asText("(no detail)")
                        + ".\n\nReply to this email to try again in the same session.\n\n- "
                        + config.elfName;
            } else {
                body = b.path("reply_body").asText("(the session produced no output)")
                        + "\n\n- " + config.elfName
                        + " (reply to this email to continue the session)";
            }
            var fake = new MailFetcher.InboundMail(
                    pr.inboundMessageId(), pr.fromAddress(), pr.subject(), "", "", "");
            sender.reply(fake, body);
            return null;
        }
    }

    public static final class MailSend implements ElfBusHandler {
        private final EmailSender sender;
        private final Set<String> allowed;

        public MailSend(EmailSender sender, Set<String> allowedRecipients) {
            this.sender = sender;
            this.allowed = allowedRecipients;
        }

        @Override public String kind() { return "mail.send"; }

        @Override
        public Map<String, Object> execute(ElfBusEnvelope env) throws Exception {
            JsonNode b = env.body();
            String to        = requireText(b, "to");
            String subject   = requireText(b, "subject");
            String body      = requireText(b, "body");
            String inReplyTo = optText(b, "in_reply_to");

            if (!allowed.contains(to.toLowerCase())) {
                // Hard failure — dead-letter on first attempt. Don't retry:
                // config doesn't change between ticks.
                throw new IllegalArgumentException(
                        "recipient '" + to + "' not in ALLOWED_RECIPIENTS");
            }

            String sentMessageId;
            try {
                sentMessageId = sender.send(to, subject, body, inReplyTo);
            } catch (MessagingException e) {
                // Transient — let the bus retry budget run its course.
                throw new RetryableException("SMTP send failed: " + e.getMessage(), e);
            }

            return Map.of(
                    "status", "ok",
                    "message_id", sentMessageId == null ? "" : sentMessageId
            );
        }
    }

    // ---- shared helpers ----

    private static String requireText(JsonNode b, String field) throws IOException {
        JsonNode v = b.get(field);
        if (v == null || !v.isTextual() || v.asText().isBlank())
            throw new IOException("missing/empty required field: " + field);
        return v.asText();
    }

    private static String optText(JsonNode b, String field) {
        JsonNode v = b.get(field);
        return (v == null || !v.isTextual()) ? null : v.asText();
    }
}

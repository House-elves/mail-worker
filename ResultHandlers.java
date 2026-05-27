import com.fasterxml.jackson.databind.JsonNode;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;

import java.util.Map;
import java.util.Optional;
import java.util.Properties;

/**
 * Handlers for `*.result` envelopes arriving in mail-worker's own inbox.
 *
 * Results don't get replies of their own — they are terminal. The handler
 * looks up the original inbound email metadata via PendingStore (keyed by
 * X-Elf-Correlation-Id, which mail-worker set to the inbound Message-Id)
 * and sends an SMTP follow-up reply to the principal.
 */
public final class ResultHandlers {

    private ResultHandlers() {}

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
}

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Sealed sum-type of every action the mail-triage agent may emit.
 *
 * The *parsing* permits-list defines the closed action surface — anything
 * the agent emits outside this list is rejected, regardless of what the
 * prompt allows. The agent proposes; the code enforces.
 *
 * Network-affecting actions are split into two classes:
 *
 *   - Local actions (Ignore, Reply) — executed directly by mail-worker.
 *   - Remote-elf actions (EnqueueGitHubIssueCreate, EnqueueGitHubComment,
 *     EnqueueGitHubLabel) — enqueued onto the elf-bus; the target elf
 *     executes them. mail-worker never holds a GitHub credential.
 *   - Direct external API (CreateCalendarEvent) — executed locally
 *     against the Google Calendar API.
 */
public sealed interface MailAction
        permits MailAction.Ignore,
                MailAction.Reply,
                MailAction.EnqueueGitHubIssueCreate,
                MailAction.EnqueueGitHubComment,
                MailAction.EnqueueGitHubLabel,
                MailAction.CreateCalendarEvent {

    /** Bundle of collaborators handed to each action's execute(). */
    record Context(
            MailFetcher.InboundMail email,
            EmailSender sender,
            ElfBusProducer bus,
            Config config,
            PendingStore pending
    ) {}

    void execute(Context ctx) throws Exception;

    // ---- concrete actions ----

    record Ignore(String reason) implements MailAction {
        @Override public void execute(Context ctx) {
            System.out.println("ignore: " + reason);
        }
    }

    record Reply(String body) implements MailAction {
        @Override public void execute(Context ctx) throws Exception {
            ctx.sender().reply(ctx.email(), body);
        }
    }

    record EnqueueGitHubIssueCreate(String repo, String title, String body,
                                    List<String> labels, List<String> assignees,
                                    String replyBody) implements MailAction {
        @Override public void execute(Context ctx) throws Exception {
            // Provenance is enforced HERE, not in the prompt — the agent can forget.
            String prefix = "Filed on behalf of " + ctx.config().principalName + " via email.";
            String bodyWithProvenance = body.contains(prefix) ? body : prefix + "\n\n" + body;

            // Stash pending-reply state BEFORE enqueueing. If the enqueue
            // succeeds and we crash before the result arrives, the pending
            // entry waits; if the enqueue fails and we don't reach it, the
            // stale entry is harmless (no result will ever arrive for it
            // and it can be cleaned up by age).
            ctx.pending().put(new PendingStore.PendingReply(
                    /* correlationId   */ ctx.email().messageId(),
                    /* fromAddress     */ ctx.email().from(),
                    /* subject         */ ctx.email().subject(),
                    /* inboundMessageId*/ ctx.email().messageId()
            ));

            ctx.bus().enqueue(
                    "github-worker",
                    "github.issue.create",
                    Map.of(
                            "repo", repo,
                            "title", title,
                            "body", bodyWithProvenance,
                            "labels", labels == null ? List.of("origin:mail", "state:new") : labels,
                            "assignees", assignees == null ? List.of() : assignees
                    ),
                    envelopeOpts(ctx, /*replyTo*/ true)
            );
            if (replyBody != null && !replyBody.isBlank()) ctx.sender().reply(ctx.email(), replyBody);
        }
    }

    record EnqueueGitHubComment(String repo, int num, String body,
                                String replyBody) implements MailAction {
        @Override public void execute(Context ctx) throws Exception {
            String prefix = "> From " + ctx.config().principalName + " via email:";
            String prefixed = body.startsWith(prefix) ? body : prefix + "\n\n" + body;
            ctx.bus().enqueue(
                    "github-worker",
                    "github.issue.comment",
                    Map.of("repo", repo, "num", num, "body", prefixed),
                    envelopeOpts(ctx, /*replyTo*/ false)
            );
            if (replyBody != null && !replyBody.isBlank()) ctx.sender().reply(ctx.email(), replyBody);
        }
    }

    /**
     * Allow-list of labels enforced here, not in the prompt — same model as
     * the existing lib/mail-actions.sh. Only `approval:granted` is permitted
     * via the mail path; everything else must go through `comment-on-issue`.
     */
    Set<String> MAIL_ALLOWED_LABELS = Set.of("approval:granted");

    record EnqueueGitHubLabel(String repo, int num, String label,
                              String replyBody) implements MailAction {
        @Override public void execute(Context ctx) throws Exception {
            if (!MAIL_ALLOWED_LABELS.contains(label)) {
                throw new IllegalArgumentException(
                        "label '" + label + "' not in mail allow-list " + MAIL_ALLOWED_LABELS);
            }
            ctx.bus().enqueue(
                    "github-worker",
                    "github.issue.label",
                    Map.of("repo", repo, "num", num, "label", label),
                    envelopeOpts(ctx, /*replyTo*/ false)
            );
            if (replyBody != null && !replyBody.isBlank()) ctx.sender().reply(ctx.email(), replyBody);
        }
    }

    record CreateCalendarEvent(String calendarId, String title, String description,
                               String startIso, String endIso,
                               String replyBody) implements MailAction {
        @Override public void execute(Context ctx) {
            // TODO: Google Calendar API client. OAuth creds live in
            // ~/.config/mail-worker/google-calendar.json (refresh token).
            // Could also be lifted into its own calendar-worker elf later,
            // at which point this becomes another bus.enqueue(...) call.
            throw new UnsupportedOperationException("calendar not implemented yet");
        }
    }

    // ---- parsing ----

    static MailAction parse(JsonNode node) {
        String action = node.path("action").asText();
        return switch (action) {
            case "ignore"            -> new Ignore(node.path("reason").asText("(none)"));
            case "reply"             -> new Reply(node.path("body").asText(""));
            case "create-issue"      -> new EnqueueGitHubIssueCreate(
                    node.path("repo").asText(),
                    node.path("title").asText(),
                    node.path("body").asText(),
                    jsonList(node.path("labels")),
                    jsonList(node.path("assignees")),
                    optStr(node, "reply_body"));
            case "comment-on-issue"  -> new EnqueueGitHubComment(
                    node.path("repo").asText(),
                    node.path("num").asInt(),
                    node.path("body").asText(),
                    optStr(node, "reply_body"));
            case "label-issue"       -> new EnqueueGitHubLabel(
                    node.path("repo").asText(),
                    node.path("num").asInt(),
                    node.path("label").asText(),
                    optStr(node, "reply_body"));
            case "create-calendar-event" -> new CreateCalendarEvent(
                    node.path("calendar_id").asText("primary"),
                    node.path("title").asText(),
                    node.path("description").asText(""),
                    node.path("start").asText(),
                    node.path("end").asText(),
                    optStr(node, "reply_body"));
            default -> throw new IllegalArgumentException("unknown action: " + action);
        };
    }

    private static List<String> jsonList(JsonNode node) {
        if (node == null || !node.isArray()) return List.of();
        return java.util.stream.StreamSupport.stream(node.spliterator(), false)
                .map(JsonNode::asText).toList();
    }

    private static String optStr(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return (v == null || v.isNull()) ? null : v.asText();
    }

    // ---- envelope ----

    private static EnvelopeOptions envelopeOpts(Context ctx, boolean replyTo) {
        return new EnvelopeOptions(
                /* correlationId */ ctx.email().messageId(),
                /* replyToElf    */ replyTo ? ctx.config().elfName() : null,
                /* provenance    */ "email; principal=" + ctx.config().principalEmail,
                /* inReplyTo     */ null,
                /* maxAttempts   */ null
        );
    }
}

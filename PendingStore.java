import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Tracks inbound emails that we enqueued onto the bus and are still
 * awaiting a result for. Keyed by the inbound email's Message-Id —
 * which is the same value we set as `X-Elf-Correlation-Id` on the
 * outgoing bus envelope, so the round-trip lookup is trivial.
 *
 * Stored as one JSON file per pending request under
 * ~/.local/state/mail-worker/pending/<safe-key>.json.
 *
 * Crash-safety: writes are best-effort. If we lose pending state, the
 * worst outcome is a result arrives with no pending entry — the reader
 * dead-letters it and an operator handles by hand. The original inbound
 * email is unaffected (the issue was still filed; just no follow-up
 * email goes out).
 */
public class PendingStore {

    private static final ObjectMapper JSON = new ObjectMapper();

    public record PendingReply(
            String correlationId,   // inbound email Message-Id (also the bus correlation_id)
            String fromAddress,     // who to email when the result comes back
            String subject,         // for the Re: subject
            String inboundMessageId // for SMTP threading
    ) {}

    private final Path dir;

    public static PendingStore open(Config config) throws IOException {
        Path d = config.stateDir().resolve("pending");
        Files.createDirectories(d);
        return new PendingStore(d);
    }

    private PendingStore(Path dir) { this.dir = dir; }

    public void put(PendingReply pr) throws IOException {
        Path file = dir.resolve(safeKey(pr.correlationId()) + ".json");
        Files.writeString(file, JSON.writeValueAsString(pr));
    }

    public Optional<PendingReply> take(String correlationId) throws IOException {
        Path file = dir.resolve(safeKey(correlationId) + ".json");
        if (!Files.exists(file)) return Optional.empty();
        byte[] bytes = Files.readAllBytes(file);
        Files.deleteIfExists(file);
        return Optional.of(JSON.readValue(bytes, PendingReply.class));
    }

    private static String safeKey(String mid) {
        if (mid == null || mid.isEmpty()) return "no-id";
        StringBuilder sb = new StringBuilder(mid.length());
        for (int i = 0; i < mid.length(); i++) {
            char c = mid.charAt(i);
            boolean ok = (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')
                      || (c >= '0' && c <= '9') || c == '.' || c == '_' || c == '-';
            sb.append(ok ? c : '_');
        }
        return sb.length() > 120 ? sb.substring(0, 120) : sb.toString();
    }
}

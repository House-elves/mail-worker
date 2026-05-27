import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Tiny file-marker idempotency store, keyed by Message-Id. Same pattern as
 * lib/state.sh in dev-team: one empty file per processed Message-Id, named
 * after a filesystem-safe key.
 *
 * For higher volume an SQLite-backed implementation would slot in here;
 * for now flat files are plenty and trivially inspectable.
 */
public class IdempotencyStore {

    private final Path dir;

    public static IdempotencyStore open(Config config) throws IOException {
        Path d = config.processedDir();
        Files.createDirectories(d);
        return new IdempotencyStore(d);
    }

    private IdempotencyStore(Path dir) { this.dir = dir; }

    public boolean contains(String messageId) {
        return Files.exists(dir.resolve(safeKey(messageId)));
    }

    public void add(String messageId) throws IOException {
        Files.createFile(dir.resolve(safeKey(messageId)));
    }

    /** Replace anything not [A-Za-z0-9._-] with underscore, truncate to 120 chars. */
    private static String safeKey(String mid) {
        if (mid == null || mid.isEmpty()) return "no-message-id";
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

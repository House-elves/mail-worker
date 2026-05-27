import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Config layout mirrors github-worker: a single ~/.config/mail-worker/config file
 * with KEY=VALUE lines. Lines starting with # are ignored.
 */
public class Config {

    static final Path CONFIG_DIR = Path.of(System.getProperty("user.home"), ".config", "mail-worker");
    static final Path CONFIG_PATH = CONFIG_DIR.resolve("config");
    static final Path STATE_DIR_DEFAULT = Path.of(
            System.getProperty("user.home"), ".local", "state", "mail-worker");
    static final Path BUS_ROOT_DEFAULT = Path.of(
            System.getProperty("user.home"), ".local", "share", "elf-bus");

    String elfName;
    String principalEmail;
    String principalName;

    String imapHost;
    int    imapPort;
    String imapUser;
    String imapPassword;

    String smtpHost;
    int    smtpPort;
    String smtpUser;
    String smtpPassword;

    Set<String> allowedSenders;     // From: addresses we accept (case-insensitive)
    String activeHours;             // e.g. "07-22"
    String agent;                   // "claude" by default
    String agentModel;              // e.g. "claude-sonnet-4-6"

    Path stateDir;
    Path busRoot;

    static Config load() {
        if (!Files.exists(CONFIG_PATH)) {
            System.err.println("Config file not found: " + CONFIG_PATH);
            System.err.println("Run: mail-worker --install");
            System.exit(1);
        }
        Map<String, String> raw = new HashMap<>();
        try {
            for (String line : Files.readAllLines(CONFIG_PATH)) {
                line = line.strip();
                if (line.isEmpty() || line.startsWith("#")) continue;
                int eq = line.indexOf('=');
                if (eq > 0) raw.put(line.substring(0, eq).strip(), line.substring(eq + 1).strip());
            }
        } catch (IOException e) {
            System.err.println("Failed to read config: " + e.getMessage());
            System.exit(1);
        }

        Config c = new Config();
        c.elfName        = raw.getOrDefault("ELF_NAME", "mail-worker");
        c.principalEmail = raw.getOrDefault("PRINCIPAL_EMAIL", "");
        c.principalName  = raw.getOrDefault("PRINCIPAL_NAME", "");
        c.imapHost       = raw.getOrDefault("IMAP_HOST", "imap.gmail.com");
        c.imapPort       = Integer.parseInt(raw.getOrDefault("IMAP_PORT", "993"));
        c.imapUser       = raw.getOrDefault("IMAP_USER", "");
        c.imapPassword   = raw.getOrDefault("IMAP_PASSWORD", "");
        c.smtpHost       = raw.getOrDefault("SMTP_HOST", "smtp.gmail.com");
        c.smtpPort       = Integer.parseInt(raw.getOrDefault("SMTP_PORT", "465"));
        c.smtpUser       = raw.getOrDefault("SMTP_USER", c.imapUser);
        c.smtpPassword   = raw.getOrDefault("SMTP_PASSWORD", c.imapPassword);
        c.allowedSenders = parseSet(raw.getOrDefault("ALLOWED_SENDERS", ""))
                                .stream().map(String::toLowerCase).collect(Collectors.toUnmodifiableSet());
        c.activeHours    = raw.getOrDefault("ACTIVE_HOURS", "07-22");
        c.agent          = raw.getOrDefault("AGENT", "claude");
        c.agentModel     = raw.getOrDefault("AGENT_MODEL", "claude-sonnet-4-6");
        c.stateDir       = Path.of(raw.getOrDefault("STATE_DIR", STATE_DIR_DEFAULT.toString()));
        c.busRoot        = Path.of(raw.getOrDefault("BUS_ROOT", BUS_ROOT_DEFAULT.toString()));
        return c;
    }

    void validate() {
        require("IMAP_USER", imapUser);
        require("IMAP_PASSWORD", imapPassword);
        require("PRINCIPAL_EMAIL", principalEmail);
        if (allowedSenders.isEmpty()) {
            System.err.println("Error: ALLOWED_SENDERS must list at least one address");
            System.exit(1);
        }
    }

    boolean isWithinActiveHours() {
        try {
            String[] parts = activeHours.split("-");
            int start = Integer.parseInt(parts[0]);
            int end   = Integer.parseInt(parts[1]);
            int hour  = LocalTime.now().getHour();
            return start <= hour && hour < end;
        } catch (Exception e) {
            return true;
        }
    }

    String   elfName()        { return elfName; }
    Path     stateDir()       { return stateDir; }
    Path     busRoot()        { return busRoot; }
    Path     lockPath()       { return stateDir.resolve("lock"); }
    Path     processedDir()   { return stateDir.resolve("processed-mail"); }
    Path     busSeenIdsPath() { return stateDir.resolve("elf-bus-seen"); }

    private static Set<String> parseSet(String csv) {
        return Arrays.stream(csv.split(","))
                .map(String::strip).filter(s -> !s.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
    }

    private static void require(String name, String value) {
        if (value == null || value.isEmpty() || "CHANGE_ME".equals(value)) {
            System.err.println("Error: " + name + " not configured in " + CONFIG_PATH);
            System.exit(1);
        }
    }
}

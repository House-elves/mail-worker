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

    Set<String> allowedSenders;     // inbound From: addresses we accept (case-insensitive)
    Set<String> sessionSenders;     // subset of senders whose mail becomes a Claude session (case-insensitive)
    Set<String> allowedRecipients;  // outbound To: addresses the mail.send handler may send to (case-insensitive)
    String activeHours;             // e.g. "07-22"
    String agent;                   // "claude" by default
    String agentModel;              // e.g. "claude-sonnet-4-6"

    Path stateDir;
    Path busRoot;

    // Customer mail: on when ~/.config/mail-worker/customer-mail.md exists.
    // Mail from anyone off the allow-list (a customer, a prospect) gets a
    // drafted answer emailed to the principal, or, when SUPPORT_AUTO_REPLY
    // is on and the case is clear cut, a direct reply copied to the principal.
    java.time.Instant supportSince;  // ignore mail received before this (no backlog on first run)
    boolean supportAutoReply;        // false = every answer is a draft for the principal
    String  supportFrom;             // From address on direct replies (a send-as alias of SMTP_USER)
    String  supportFromName;         // display name on direct replies
    String  supportModel;            // model for customer mail; defaults to AGENT_MODEL
    int     supportRepliesPerDay;    // direct replies to one sender per 24h before it falls back to drafts
    List<String> supportFolders;     // where customer mail lands; a Gmail filter can skip the inbox

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
        // Recipient allow-list for the mail.send bus kind. Defaults to the
        // principal email — the safest sender-equals-recipient baseline.
        c.allowedRecipients = parseSet(raw.getOrDefault("ALLOWED_RECIPIENTS", c.principalEmail))
                                .stream().map(String::toLowerCase).collect(Collectors.toUnmodifiableSet());
        // Senders whose (DKIM-verified) mail is routed to session-worker as a
        // full Claude session instead of zero-tool triage. Default empty:
        // the feature is off until the operator opts addresses in.
        c.sessionSenders = parseSet(raw.getOrDefault("SESSION_SENDERS", ""))
                                .stream().map(String::toLowerCase).collect(Collectors.toUnmodifiableSet());
        c.activeHours    = raw.getOrDefault("ACTIVE_HOURS", "07-22");
        c.agent          = raw.getOrDefault("AGENT", "claude");
        c.agentModel     = raw.getOrDefault("AGENT_MODEL", "claude-sonnet-4-6");
        c.stateDir       = Path.of(raw.getOrDefault("STATE_DIR", STATE_DIR_DEFAULT.toString()));
        c.busRoot        = Path.of(raw.getOrDefault("BUS_ROOT", BUS_ROOT_DEFAULT.toString()));
        String since     = raw.getOrDefault("SUPPORT_SINCE", "");
        // Unset: the last three days, so turning it on picks up what is waiting
        // but never answers a months-old backlog.
        c.supportSince   = since.isEmpty() ? java.time.Instant.now().minus(java.time.Duration.ofDays(3))
                                         : java.time.Instant.parse(since);
        c.supportAutoReply = Boolean.parseBoolean(raw.getOrDefault("SUPPORT_AUTO_REPLY", "false"));
        c.supportFrom    = raw.getOrDefault("SUPPORT_FROM", c.smtpUser);
        c.supportFromName = raw.getOrDefault("SUPPORT_FROM_NAME", c.elfName);
        c.supportModel   = raw.getOrDefault("SUPPORT_MODEL", c.agentModel);
        c.supportRepliesPerDay = Integer.parseInt(raw.getOrDefault("SUPPORT_REPLIES_PER_DAY", "2"));
        c.supportFolders = Arrays.stream(raw.getOrDefault("SUPPORT_FOLDERS", "INBOX").split(","))
                .map(String::strip).filter(f -> !f.isEmpty()).toList();
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

    String      elfName()           { return elfName; }
    Path        stateDir()          { return stateDir; }
    Path        busRoot()           { return busRoot; }
    Path        lockPath()          { return stateDir.resolve("lock"); }
    Path        processedDir()      { return stateDir.resolve("processed-mail"); }
    Path        busSeenIdsPath()    { return stateDir.resolve("elf-bus-seen"); }
    Path        attachmentsDir()    { return stateDir.resolve("attachments"); }
    Set<String> allowedRecipients() { return allowedRecipients; }
    Path        supportPromptPath() { return CONFIG_DIR.resolve("customer-mail.md"); }
    boolean     supportEnabled()    { return Files.exists(supportPromptPath()); }
    Path        supportRepliesLog() { return stateDir.resolve("customer-replies.log"); }

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

import java.io.BufferedReader;
import java.io.Console;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Interactive setup for mail-worker.
 *
 * Run via {@code jbang mail-worker --install}. Walks the operator through:
 *   1. IMAP / SMTP credentials
 *   2. Allow-listed senders
 *   3. Triage prompt source (URL or local path)
 *   4. Active hours + cron cadence
 *   5. Claude model
 *
 * On completion, writes:
 *   - {@code ~/.config/mail-worker/config}        (KEY=VALUE)
 *   - {@code ~/.config/mail-worker/mail-triage.md} (prompt body)
 *   - {@code ~/.config/systemd/user/mail-worker.service}
 *   - {@code ~/.config/systemd/user/mail-worker.timer}
 *   - {@code ~/.config/systemd/user/mail-worker-ui.service}
 *
 * And bootstraps:
 *   - {@code ~/.local/state/mail-worker/} (state)
 *   - {@code ~/.local/share/elf-bus/mail-worker/Maildir/{tmp,new,cur,.dead/...}} (inbox)
 *
 * The operator finishes with one {@code systemctl --user enable --now} command.
 */
public final class Installer {

    private static final String DEFAULT_PROMPT_URL =
            "https://raw.githubusercontent.com/House-elves/mail-worker/main/mail-triage.md";

    private Installer() {}

    public static void run() {
        Console console = System.console();
        BufferedReader fallback = (console == null)
                ? new BufferedReader(new InputStreamReader(System.in))
                : null;
        if (console == null) {
            System.out.println("(no TTY; reading from stdin without echo for passwords is disabled)");
        }

        Map<String, String> cfg = new LinkedHashMap<>();
        cfg.put("ELF_NAME",          prompt(console, fallback, "Elf name",                    "mail-worker"));
        cfg.put("PRINCIPAL_EMAIL",   require(prompt(console, fallback, "Principal email",     "")));
        cfg.put("PRINCIPAL_NAME",    prompt(console, fallback, "Principal display name",     "Bin Chicken"));

        cfg.put("IMAP_HOST",         prompt(console, fallback, "IMAP host",                   "imap.gmail.com"));
        cfg.put("IMAP_PORT",         prompt(console, fallback, "IMAP port",                   "993"));
        cfg.put("IMAP_USER",         require(prompt(console, fallback, "IMAP user (email)",   "")));
        cfg.put("IMAP_PASSWORD",     require(promptSecret(console, fallback, "IMAP app password")));

        cfg.put("SMTP_HOST",         prompt(console, fallback, "SMTP host",                   "smtp.gmail.com"));
        cfg.put("SMTP_PORT",         prompt(console, fallback, "SMTP port",                   "465"));
        cfg.put("SMTP_USER",         prompt(console, fallback, "SMTP user",                   cfg.get("IMAP_USER")));
        String smtpPw = promptSecret(console, fallback, "SMTP password (blank = same as IMAP)");
        cfg.put("SMTP_PASSWORD",     smtpPw.isEmpty() ? cfg.get("IMAP_PASSWORD") : smtpPw);

        cfg.put("ALLOWED_SENDERS",   require(prompt(console, fallback, "Allowed senders (comma-sep)", "")));
        cfg.put("SESSION_SENDERS",   prompt(console, fallback,
                "Session senders - mail from these becomes a Claude session; blank disables (comma-sep)", ""));
        cfg.put("ALLOWED_RECIPIENTS", prompt(console, fallback,
                "Allowed outbound recipients for mail.send (comma-sep)",
                cfg.get("PRINCIPAL_EMAIL")));
        cfg.put("ACTIVE_HOURS",      prompt(console, fallback, "Active hours (e.g. 07-22)",   "07-22"));
        cfg.put("AGENT_MODEL",       prompt(console, fallback, "Claude model",                "claude-sonnet-4-6"));

        String promptSource = prompt(console, fallback,
                "Triage prompt source (URL or local path)", DEFAULT_PROMPT_URL);
        String cadenceMin   = prompt(console, fallback, "Run every N minutes", "5");

        try {
            writeConfig(cfg);
            installPrompt(promptSource);
            bootstrapDirs(cfg.get("ELF_NAME"));
            writeSystemd(cadenceMin);
            printNextSteps();
        } catch (IOException | InterruptedException e) {
            System.err.println("install failed: " + e.getMessage());
            System.exit(1);
        }
    }

    // ---- prompts ----

    private static String prompt(Console console, BufferedReader fallback, String label, String dflt) {
        String suffix = dflt.isEmpty() ? "" : " [" + dflt + "]";
        try {
            String s;
            if (console != null) {
                s = console.readLine("%s%s: ", label, suffix);
            } else {
                System.out.print(label + suffix + ": ");
                s = fallback.readLine();
            }
            if (s == null || s.isBlank()) return dflt;
            return s.strip();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static String promptSecret(Console console, BufferedReader fallback, String label) {
        if (console != null) {
            char[] c = console.readPassword("%s: ", label);
            return c == null ? "" : new String(c).strip();
        }
        return prompt(null, fallback, label, "");  // no-echo not available without TTY
    }

    private static String require(String value) {
        if (value == null || value.isBlank()) {
            System.err.println("required field empty; aborting install");
            System.exit(1);
        }
        return value;
    }

    // ---- writers ----

    private static void writeConfig(Map<String, String> cfg) throws IOException {
        Files.createDirectories(Config.CONFIG_DIR);
        StringBuilder sb = new StringBuilder();
        sb.append("# mail-worker config — written by --install on ").append(java.time.LocalDate.now()).append('\n');
        cfg.forEach((k, v) -> sb.append(k).append('=').append(v).append('\n'));
        Files.writeString(Config.CONFIG_PATH, sb.toString());
        System.out.println("wrote " + Config.CONFIG_PATH);
    }

    private static void installPrompt(String source) throws IOException, InterruptedException {
        Path dest = Config.CONFIG_DIR.resolve("mail-triage.md");
        byte[] body;
        if (source.startsWith("http://") || source.startsWith("https://")) {
            HttpResponse<byte[]> resp = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(URI.create(source)).build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            if (resp.statusCode() != 200) {
                throw new IOException("prompt fetch failed: HTTP " + resp.statusCode());
            }
            body = resp.body();
        } else {
            body = Files.readAllBytes(Path.of(source));
        }
        Files.write(dest, body);
        System.out.println("wrote " + dest + " (" + body.length + " bytes)");
    }

    private static void bootstrapDirs(String elfName) throws IOException {
        Files.createDirectories(Config.STATE_DIR_DEFAULT);
        Files.createDirectories(Config.STATE_DIR_DEFAULT.resolve("pending"));
        Files.createDirectories(Config.STATE_DIR_DEFAULT.resolve("processed-mail"));
        Path inbox = Config.BUS_ROOT_DEFAULT.resolve(elfName).resolve("Maildir");
        for (String sub : new String[]{"tmp", "new", "cur", ".dead/tmp", ".dead/new", ".dead/cur"}) {
            Files.createDirectories(inbox.resolve(sub));
        }
        System.out.println("bootstrapped " + inbox);
    }

    private static void writeSystemd(String cadenceMin) throws IOException {
        Path unitDir = Path.of(System.getProperty("user.home"), ".config", "systemd", "user");
        Files.createDirectories(unitDir);

        String service = """
                [Unit]
                Description=mail-worker (poll inbound mail, dispatch to elf-bus)
                After=network-online.target

                [Service]
                Type=oneshot
                ExecStart=/usr/bin/env jbang mail-worker --once
                """;

        String timer = String.format("""
                [Unit]
                Description=mail-worker every %s minutes

                [Timer]
                OnCalendar=*:0/%s
                Persistent=true

                [Install]
                WantedBy=timers.target
                """, cadenceMin, cadenceMin);

        String uiService = """
                [Unit]
                Description=mail-worker-ui (Quarkus dashboard)
                After=network-online.target

                [Service]
                ExecStart=/usr/bin/env jbang mail-worker-ui
                Restart=on-failure
                RestartSec=5s

                [Install]
                WantedBy=default.target
                """;

        Files.writeString(unitDir.resolve("mail-worker.service"),    service);
        Files.writeString(unitDir.resolve("mail-worker.timer"),      timer);
        Files.writeString(unitDir.resolve("mail-worker-ui.service"), uiService);
        System.out.println("wrote systemd units to " + unitDir);
    }

    private static void printNextSteps() {
        System.out.println();
        System.out.println("Install complete. Next:");
        System.out.println();
        System.out.println("  systemctl --user daemon-reload");
        System.out.println("  systemctl --user enable --now mail-worker.timer mail-worker-ui.service");
        System.out.println();
        System.out.println("Dashboard: http://localhost:7479");
        System.out.println("Logs:      journalctl --user -fu mail-worker.service");
    }
}

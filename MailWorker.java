///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 21+
//DEPS info.picocli:picocli:4.7.6
//DEPS com.fasterxml.jackson.core:jackson-databind:2.17.2
//DEPS jakarta.mail:jakarta.mail-api:2.1.3
//DEPS org.eclipse.angus:angus-mail:2.0.3
//SOURCES Config.java MailFetcher.java MailTriager.java MailAction.java EmailSender.java IdempotencyStore.java
//SOURCES PendingStore.java BusHandlers.java Installer.java CustomerMail.java
//SOURCES https://raw.githubusercontent.com/House-elves/elf-bus-common/v1.0.1/EnvelopeOptions.java
//SOURCES https://raw.githubusercontent.com/House-elves/elf-bus-common/v1.0.1/ElfBusEnvelope.java
//SOURCES https://raw.githubusercontent.com/House-elves/elf-bus-common/v1.0.1/ElfBusHandler.java
//SOURCES https://raw.githubusercontent.com/House-elves/elf-bus-common/v1.0.1/ElfBusProducer.java
//SOURCES https://raw.githubusercontent.com/House-elves/elf-bus-common/v1.0.1/ElfBusInboxes.java
//SOURCES https://raw.githubusercontent.com/House-elves/elf-bus-common/v1.0.1/ElfBusSeenIds.java
//SOURCES https://raw.githubusercontent.com/House-elves/elf-bus-common/v1.0.1/FileSystemBus.java
//SOURCES https://raw.githubusercontent.com/House-elves/elf-bus-common/v1.0.1/ElfBusConsumer.java
//SOURCES https://raw.githubusercontent.com/House-elves/elf-bus-common/v1.0.1/InMemoryBus.java

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

@Command(name = "mail-worker",
        mixinStandardHelpOptions = true,
        description = "House Elf that triages inbound email and dispatches actions via the elf-bus.")
public class MailWorker implements Runnable {

    @Option(names = "--install", description = "Interactive setup; prompts for IMAP/SMTP creds and writes config.")
    boolean install;

    @Option(names = "--once", description = "Run a single poll cycle and exit (default).")
    boolean once = true;

    @Option(names = "--customer-test", paramLabel = "MESSAGE_ID",
            description = "Run the customer-mail path on one message (read or not) and email the outcome to the principal only. Nothing goes to the customer and nothing is marked.")
    String customerTest;

    public static void main(String[] args) {
        System.exit(new CommandLine(new MailWorker()).execute(args));
    }

    @Override
    public void run() {
        if (install) {
            Installer.run();
            return;
        }

        Config config = Config.load();
        config.validate();

        if (customerTest != null) {
            runCustomerTest(config, customerTest);
            return;
        }

        if (!config.isWithinActiveHours()) {
            System.out.println("outside active hours; exiting");
            return;
        }

        try (var lock = SingleInstanceLock.tryAcquire(config.lockPath())) {
            if (lock == null) {
                System.err.println("another mail-worker is running; exiting");
                return;
            }

            var seen     = IdempotencyStore.open(config);
            var fetcher  = new MailFetcher(config);
            var triager  = new MailTriager(config);
            var bus      = new FileSystemBus(config.busRoot(), config.elfName());
            var sender   = new EmailSender(config);
            var pending  = PendingStore.open(config);

            // 1. Drain any bus messages from peer elves first. Two kinds:
            //    - github.issue.create.result: send the URL follow-up to the
            //      original mail sender (terminal; no result emitted).
            //    - mail.send: send an outbound email on behalf of a peer elf;
            //      emit a mail.send.result with the SMTP Message-Id.
            //
            //    mail.send emits a result, so the consumer needs a replyProducer.
            var reader = new ElfBusConsumer(
                    config.busRoot(),
                    config.elfName(),
                    config.busSeenIdsPath(),
                    List.of(
                            new BusHandlers.IssueCreated(sender, pending, config),
                            new BusHandlers.SessionResult(sender, pending, config),
                            new BusHandlers.MailSend(sender, config.allowedRecipients())
                    ),
                    /*replyProducer*/ bus
            );
            try {
                reader.poll();
            } catch (IOException e) {
                System.err.println("elf-bus reader: " + e.getMessage());
            }

            // 2. Triage new inbound email.
            List<MailFetcher.InboundMail> batch = fetcher.fetchUnseenAllowListed();
            System.out.printf("fetched %d allow-listed message(s)%n", batch.size());

            for (var msg : batch) {
                if (seen.contains(msg.messageId())) {
                    System.out.println("already processed " + msg.messageId() + "; skipping");
                    continue;
                }
                System.out.printf("triaging from=%s subj=%s%n", msg.from(), msg.subject());
                try {
                    // Session routing is decided IN CODE, never by the triage
                    // agent: a SESSION_SENDERS address with a passing DKIM
                    // result goes straight to session-worker ("do what the
                    // mail says"). A session sender that fails DKIM smells
                    // like spoofing - it falls through to zero-tool triage,
                    // where the worst an attacker gets is a polite reply.
                    MailAction action = isSessionMail(config, msg)
                            ? new MailAction.RunSession()
                            : triager.triage(msg);
                    String actionName = action.getClass().getSimpleName();
                    System.out.println("action=" + actionName);
                    action.execute(new MailAction.Context(msg, sender, bus, config, pending));
                    seen.add(msg.messageId());
                    logTriage(config, msg, actionName, null);
                } catch (Exception e) {
                    System.err.printf("execute failed for %s: %s%n", msg.messageId(), e.getMessage());
                    logTriage(config, msg, null, e.getMessage());
                    // Leave UNSEEN on the IMAP side and unmarked locally → retry next tick.
                }
            }

            // 3. Customer mail: people off the allow-list writing in. Left
            //    unread unless answered, so a person still sees whatever this
            //    passes over. See CustomerMail for what is checked in code.
            if (config.supportEnabled()) {
                var customers = new CustomerMail(config, sender);
                List<MailFetcher.InboundMail> waiting;
                try {
                    waiting = fetcher.fetchUnseenCustomerMail(seen::contains);
                } catch (RuntimeException e) {
                    System.err.println("customer mail fetch: " + e.getMessage());
                    waiting = List.of();
                }
                System.out.printf("fetched %d customer message(s)%n", waiting.size());
                for (var msg : waiting) {
                    System.out.printf("customer mail from=%s subj=%s%n", msg.from(), msg.subject());
                    try {
                        var decision = customers.check(msg, customers.propose(msg));
                        String actionName = customers.execute(msg, decision, false);
                        System.out.println("action=" + actionName);
                        seen.add(msg.messageId());
                        if (!(decision instanceof CustomerMail.Ignore)) fetcher.markSeen(msg.messageId());
                        logTriage(config, msg, actionName, null);
                    } catch (Exception e) {
                        System.err.printf("customer mail failed for %s: %s%n", msg.messageId(), e.getMessage());
                        logTriage(config, msg, null, e.getMessage());
                        // Retry a few ticks, then hand it to a person rather than calling Claude forever.
                        try {
                            if (customers.failedOnce(msg.messageId()) >= 3) {
                                customers.execute(msg, new CustomerMail.Draft("", "",
                                        "Bin Chicken could not read this email after three tries, so it needs you."), false);
                                seen.add(msg.messageId());
                                fetcher.markSeen(msg.messageId());
                                logTriage(config, msg, "CustomerGaveUp", e.getMessage());
                            }
                        } catch (Exception again) {
                            System.err.println("could not hand over failed customer mail: " + again.getMessage());
                        }
                    }
                }
            }
        } catch (IOException e) {
            System.err.println("mail-worker: " + e.getMessage());
            System.exit(1);
        }
    }

    private static void runCustomerTest(Config config, String messageId) {
        try {
            var msg = new MailFetcher(config).fetchById(messageId);
            var customers = new CustomerMail(config, new EmailSender(config));
            var proposed = customers.propose(msg);
            var checked = customers.check(msg, proposed);
            System.out.println("proposed=" + proposed);
            System.out.println("checked=" + checked);
            System.out.println("action=" + customers.execute(msg, checked, true));
        } catch (Exception e) {
            System.err.println("customer test failed: " + e.getMessage());
            System.exit(1);
        }
    }

    private static boolean isSessionMail(Config config, MailFetcher.InboundMail msg) {
        if (!config.sessionSenders.contains(msg.from().toLowerCase())) return false;
        // Not dkimPass(): any signature from any domain satisfies that, so a
        // forged From signed by the forger's own domain got a session.
        if (msg.fromDomainVerified()) return true;
        System.out.printf("session sender %s not verified (DKIM/DMARC for the From domain) - falling back to triage%n", msg.from());
        return false;
    }

    /**
     * Append one JSON-line per processed email to triage.log.
     * Read by MailWorkerUI for the dashboard's Recent Triages section.
     */
    private static void logTriage(Config cfg, MailFetcher.InboundMail msg, String action, String error) {
        try {
            Path log = cfg.stateDir().resolve("triage.log");
            Files.createDirectories(log.getParent());
            var record = java.util.Map.of(
                    "timestamp",  java.time.Instant.now().toString(),
                    "from",       msg.from()       == null ? "" : msg.from(),
                    "subject",    msg.subject()    == null ? "" : msg.subject(),
                    "message_id", msg.messageId()  == null ? "" : msg.messageId(),
                    "action",     action == null ? "ERROR" : action,
                    "error",      error  == null ? ""      : error
            );
            String line = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(record);
            Files.writeString(log, line + System.lineSeparator(),
                    java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.APPEND);
        } catch (Exception ignore) {
            // Triage logging is best-effort; never crash the worker over it.
        }
    }

    /**
     * flock-style single-instance guard. The OS releases the lock on process death;
     * we also release explicitly via try-with-resources on a clean exit.
     */
    static final class SingleInstanceLock implements AutoCloseable {
        private final RandomAccessFile raf;
        private final FileLock lock;

        static SingleInstanceLock tryAcquire(Path path) throws IOException {
            Files.createDirectories(path.getParent());
            var raf = new RandomAccessFile(path.toFile(), "rw");
            FileLock fl = raf.getChannel().tryLock();
            if (fl == null) { raf.close(); return null; }
            return new SingleInstanceLock(raf, fl);
        }

        private SingleInstanceLock(RandomAccessFile raf, FileLock lock) {
            this.raf = raf; this.lock = lock;
        }

        @Override
        public void close() throws IOException {
            try { lock.release(); } finally { raf.close(); }
        }
    }
}

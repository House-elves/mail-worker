///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 21+
//DEPS info.picocli:picocli:4.7.6
//DEPS com.fasterxml.jackson.core:jackson-databind:2.17.2
//DEPS jakarta.mail:jakarta.mail-api:2.1.3
//DEPS org.eclipse.angus:angus-mail:2.0.3
//SOURCES Config.java MailFetcher.java MailTriager.java MailAction.java EmailSender.java IdempotencyStore.java
//SOURCES PendingStore.java BusHandlers.java Installer.java
//SOURCES https://raw.githubusercontent.com/House-elves/elf-bus-common/v1.0.0/EnvelopeOptions.java
//SOURCES https://raw.githubusercontent.com/House-elves/elf-bus-common/v1.0.0/ElfBusEnvelope.java
//SOURCES https://raw.githubusercontent.com/House-elves/elf-bus-common/v1.0.0/ElfBusHandler.java
//SOURCES https://raw.githubusercontent.com/House-elves/elf-bus-common/v1.0.0/ElfBusProducer.java
//SOURCES https://raw.githubusercontent.com/House-elves/elf-bus-common/v1.0.0/ElfBusInboxes.java
//SOURCES https://raw.githubusercontent.com/House-elves/elf-bus-common/v1.0.0/ElfBusSeenIds.java
//SOURCES https://raw.githubusercontent.com/House-elves/elf-bus-common/v1.0.0/FileSystemBus.java
//SOURCES https://raw.githubusercontent.com/House-elves/elf-bus-common/v1.0.0/ElfBusConsumer.java
//SOURCES https://raw.githubusercontent.com/House-elves/elf-bus-common/v1.0.0/InMemoryBus.java

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
                    MailAction action = triager.triage(msg);
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
        } catch (IOException e) {
            System.err.println("mail-worker: " + e.getMessage());
            System.exit(1);
        }
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

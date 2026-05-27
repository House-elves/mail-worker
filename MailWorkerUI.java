///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 21+
//DEPS io.quarkus:quarkus-bom:3.15.0@pom
//DEPS io.quarkus:quarkus-rest
//DEPS io.quarkus:quarkus-rest-jackson
//DEPS com.fasterxml.jackson.core:jackson-databind:2.17.2
//DEPS jakarta.mail:jakarta.mail-api:2.1.3
//DEPS org.eclipse.angus:angus-mail:2.0.3
//Q:CONFIG quarkus.http.port=7479
//Q:CONFIG quarkus.banner.enabled=false
//SOURCES Config.java PendingStore.java
//SOURCES https://raw.githubusercontent.com/House-elves/elf-bus-common/v1.0.0/ElfBusEnvelope.java
//SOURCES https://raw.githubusercontent.com/House-elves/elf-bus-common/v1.0.0/ElfBusInboxes.java

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import java.io.InputStream;
import java.nio.file.Files;
import java.util.*;
import java.util.stream.Stream;

/**
 * Read-only dashboard for mail-worker. Runs as a long-lived Quarkus service
 * (separate unit from the timer-driven worker). The Trigger Now button
 * subprocess-spawns {@code jbang mail-worker --once} — the worker is otherwise
 * driven by its own systemd timer.
 */
@Path("/")
public class MailWorkerUI {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Session SESSION = Session.getInstance(new Properties());
    private static final Config CFG = Config.load();

    @GET
    @Produces(MediaType.TEXT_HTML)
    public String index() {
        return INDEX_HTML;
    }

    public record TriageRow(String ts, String from, String subject, String action, String error) {}
    public record PendingRow(String correlationId, String from, String subject) {}
    public record DeadLetterRow(String file, String kind, String reason, String from) {}

    @GET @Path("api/triages") @Produces(MediaType.APPLICATION_JSON)
    public List<TriageRow> triages() {
        java.nio.file.Path log = CFG.stateDir().resolve("triage.log");
        if (!Files.exists(log)) return List.of();
        try (Stream<String> lines = Files.lines(log)) {
            List<String> all = lines.toList();
            int from = Math.max(0, all.size() - 50);
            List<TriageRow> rows = new ArrayList<>();
            for (int i = all.size() - 1; i >= from; i--) {
                try {
                    JsonNode n = JSON.readTree(all.get(i));
                    rows.add(new TriageRow(
                            n.path("timestamp").asText(""),
                            n.path("from").asText(""),
                            n.path("subject").asText(""),
                            n.path("action").asText(""),
                            n.path("error").asText("")
                    ));
                } catch (Exception ignore) {}
            }
            return rows;
        } catch (Exception e) {
            return List.of();
        }
    }

    @GET @Path("api/pending") @Produces(MediaType.APPLICATION_JSON)
    public List<PendingRow> pending() {
        java.nio.file.Path dir = CFG.stateDir().resolve("pending");
        if (!Files.isDirectory(dir)) return List.of();
        try (Stream<java.nio.file.Path> s = Files.list(dir)) {
            return s.map(this::readPending).filter(Objects::nonNull).toList();
        } catch (Exception e) {
            return List.of();
        }
    }

    private PendingRow readPending(java.nio.file.Path p) {
        try {
            JsonNode n = JSON.readTree(Files.readAllBytes(p));
            return new PendingRow(
                    n.path("correlationId").asText(""),
                    n.path("fromAddress").asText(""),
                    n.path("subject").asText("")
            );
        } catch (Exception e) {
            return null;
        }
    }

    @GET @Path("api/dead-letters") @Produces(MediaType.APPLICATION_JSON)
    public List<DeadLetterRow> deadLetters() {
        java.nio.file.Path dir = ElfBusInboxes.inbox(CFG.busRoot(), CFG.elfName())
                .resolve(".dead").resolve("new");
        if (!Files.isDirectory(dir)) return List.of();
        try (Stream<java.nio.file.Path> s = Files.list(dir)) {
            return s.map(this::readDeadLetter).filter(Objects::nonNull).toList();
        } catch (Exception e) {
            return List.of();
        }
    }

    private DeadLetterRow readDeadLetter(java.nio.file.Path p) {
        try (InputStream in = Files.newInputStream(p)) {
            MimeMessage m = new MimeMessage(SESSION, in);
            return new DeadLetterRow(
                    p.getFileName().toString(),
                    firstHeader(m, "X-Elf-Kind"),
                    firstHeader(m, "X-Elf-Dead-Reason"),
                    firstHeader(m, "From")
            );
        } catch (Exception e) {
            return new DeadLetterRow(p.getFileName().toString(), "?", e.getMessage(), "?");
        }
    }

    private static String firstHeader(MimeMessage m, String name) {
        try {
            String[] h = m.getHeader(name);
            return (h == null || h.length == 0) ? "" : h[0];
        } catch (Exception e) { return ""; }
    }

    @POST @Path("api/trigger") @Produces(MediaType.TEXT_PLAIN)
    public String trigger() throws Exception {
        new ProcessBuilder("jbang", "mail-worker", "--once")
                .redirectErrorStream(true)
                .redirectOutput(CFG.stateDir().resolve("trigger.log").toFile())
                .start();
        return "triggered";
    }

    /**
     * The page is static; only the data tables are rebuilt via DOM APIs.
     * Untrusted strings (email From, Subject, dead-letter reasons) are
     * rendered via {@code textContent} only — never assigned to {@code innerHTML}.
     */
    private static final String INDEX_HTML = """
            <!doctype html>
            <html lang="en">
            <head>
              <meta charset="utf-8">
              <title>mail-worker</title>
              <style>
                body { font-family: -apple-system, sans-serif; max-width: 1000px; margin: 2em auto; padding: 0 1em; }
                h1 { margin-bottom: 0.2em; }
                h2 { margin-top: 1.8em; border-bottom: 1px solid #ddd; padding-bottom: 0.3em; }
                table { width: 100%; border-collapse: collapse; }
                th, td { text-align: left; padding: 0.4em 0.6em; border-bottom: 1px solid #eee; font-size: 14px; }
                th { background: #f5f5f5; }
                .muted { color: #888; }
                .err { color: #c00; }
                button { padding: 0.5em 1em; font-size: 14px; cursor: pointer; }
                .empty { font-style: italic; color: #888; padding: 0.5em; }
              </style>
            </head>
            <body>
              <h1>mail-worker</h1>
              <p class="muted">Dashboard for the email-triage House Elf.</p>
              <button id="trigger">Trigger poll now</button>

              <h2>Recent triages <span class="muted" id="tr-count"></span></h2>
              <div id="triages" class="empty">loading…</div>

              <h2>Pending replies <span class="muted" id="pe-count"></span></h2>
              <div id="pending" class="empty">loading…</div>

              <h2>Dead-letter queue <span class="muted" id="dl-count"></span></h2>
              <div id="dead" class="empty">loading…</div>

              <script>
                async function refresh() {
                  const [t, p, d] = await Promise.all([
                    fetch('/api/triages').then(r => r.json()),
                    fetch('/api/pending').then(r => r.json()),
                    fetch('/api/dead-letters').then(r => r.json())
                  ]);
                  render('triages', t, ['ts','from','subject','action','error'],
                                       ['When','From','Subject','Action','Error']);
                  render('pending', p, ['correlationId','from','subject'],
                                       ['Correlation Id','From','Subject']);
                  render('dead',    d, ['file','kind','reason','from'],
                                       ['File','Kind','Reason','From']);
                  setBadge('tr-count', t.length);
                  setBadge('pe-count', p.length);
                  setBadge('dl-count', d.length);
                }
                function setBadge(id, n) {
                  document.getElementById(id).textContent = n ? '(' + n + ')' : '';
                }
                function render(id, rows, fields, headers) {
                  const host = document.getElementById(id);
                  // Clear (no user content involved in the empty assignment).
                  while (host.firstChild) host.removeChild(host.firstChild);
                  host.className = '';
                  if (!rows.length) {
                    const div = document.createElement('div');
                    div.className = 'empty';
                    div.textContent = 'none';
                    host.appendChild(div);
                    return;
                  }
                  const table = document.createElement('table');
                  const headRow = document.createElement('tr');
                  for (const h of headers) {
                    const th = document.createElement('th');
                    th.textContent = h;     // textContent, not innerHTML
                    headRow.appendChild(th);
                  }
                  table.appendChild(headRow);
                  for (const r of rows) {
                    const tr = document.createElement('tr');
                    for (const f of fields) {
                      const td = document.createElement('td');
                      const v = (r[f] || '').toString();
                      if (f === 'error' && v) td.className = 'err';
                      td.textContent = v;   // textContent, not innerHTML
                      tr.appendChild(td);
                    }
                    table.appendChild(tr);
                  }
                  host.appendChild(table);
                }
                document.getElementById('trigger').addEventListener('click', async () => {
                  await fetch('/api/trigger', { method: 'POST' });
                  setTimeout(refresh, 1500);
                });
                refresh();
                setInterval(refresh, 10000);
              </script>
            </body>
            </html>
            """;
}

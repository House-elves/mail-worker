# mail-worker

A [House Elf](https://github.com/House-elves) that reads inbound email, triages each message with Claude Code, and dispatches the resulting action via the [elf-bus](https://github.com/House-elves/elf-bus-common).

mail-worker never holds a GitHub credential. When triage decides an email should become a GitHub issue (or comment, or label), the action is enqueued onto the elf-bus and [github-worker](https://github.com/House-elves/github-worker) carries it out — then sends back a `*.result` envelope with the issue URL, which mail-worker forwards to the original sender as a threaded SMTP reply.

## What it does

### Triage actions (inbound email)

The triage agent picks exactly one action from this menu per inbound email:

| Action | Outcome |
|---|---|
| `ignore` | Drop the email; do nothing. |
| `reply` | Send a plain-text SMTP reply, threaded via `In-Reply-To`. |
| `create-issue` | Enqueue `github.issue.create` onto the bus; auto-reply to sender with the issue URL when the result comes back. |
| `comment-on-issue` | Enqueue `github.issue.comment` onto the bus. |
| `label-issue` | Enqueue `github.issue.label` onto the bus. Restricted to the `approval:granted` label. |

### Bus kinds it consumes

mail-worker also processes envelopes that peer elves enqueue into its own inbox:

| Kind | Action |
|---|---|
| `github.issue.create.result` | Sends SMTP follow-up to the original mail sender with the new issue URL. |
| `mail.send` | Sends an outbound email on behalf of the producer elf. Gated by `ALLOWED_RECIPIENTS`. Emits `mail.send.result` with the SMTP Message-Id. |

## Install

```bash
# Install the worker and the dashboard
jbang app install mail-worker@House-elves/mail-worker
jbang app install mail-worker-ui@House-elves/mail-worker

# Interactive setup — prompts for IMAP/SMTP creds, allow-list, schedule
mail-worker --install

# Enable the systemd units the installer wrote
systemctl --user daemon-reload
systemctl --user enable --now mail-worker.timer mail-worker-ui.service
```

The dashboard runs at <http://localhost:7479> and shows recent triages, pending replies, and the dead-letter queue.

## Configuration

Stored in `~/.config/mail-worker/config` as `KEY=VALUE` lines:

| Key | Required | Default | Description |
|---|---|---|---|
| `ELF_NAME` | no | `mail-worker` | Identity on the elf-bus. |
| `PRINCIPAL_EMAIL` | yes | — | Whose mailbox is being triaged. Appears in provenance lines. |
| `PRINCIPAL_NAME` | no | — | Display name for signed SMTP replies. |
| `IMAP_HOST` / `IMAP_PORT` / `IMAP_USER` / `IMAP_PASSWORD` | yes | `imap.gmail.com:993` | Incoming mail. App passwords recommended. |
| `SMTP_HOST` / `SMTP_PORT` / `SMTP_USER` / `SMTP_PASSWORD` | yes | `smtp.gmail.com:465` | Outbound replies. |
| `ALLOWED_SENDERS` | yes | — | Comma-separated inbound `From:` addresses allowed to reach triage. |
| `ALLOWED_RECIPIENTS` | no | `PRINCIPAL_EMAIL` | Comma-separated outbound `To:` addresses the `mail.send` bus handler may send to. Defaults to just the principal. |
| `ACTIVE_HOURS` | no | `07-22` | Only triage between these hours. |
| `AGENT_MODEL` | no | `claude-sonnet-4-6` | Which Claude model the triage agent uses. |
| `BUS_ROOT` | no | `$XDG_DATA_HOME/elf-bus` | Where peer inboxes live. |
| `STATE_DIR` | no | `$XDG_STATE_HOME/mail-worker` | Local state (logs, idempotency, pending replies). |

## Security model

mail-worker enforces five invariants regardless of what the triage agent or a producer elf asks for:

1. **Sender allow-list (inbound).** Only mail from `ALLOWED_SENDERS` reaches the triage agent. Everything else is silently dropped before any Claude call.
2. **Recipient allow-list (outbound).** The `mail.send` bus handler refuses any `to:` address not in `ALLOWED_RECIPIENTS`. Forbidden recipients dead-letter on the first attempt — no retry — so a misbehaving producer can't loop on a rejected address.
3. **Zero-tool triage.** Triage runs in `runBare` mode — no tools, no filesystem, no shell. The email body is fenced in the system prompt and explicitly marked as untrusted data.
4. **Action allow-list.** Enforced in code, not in the prompt. The label action accepts only `approval:granted`; forbidden labels are rejected by the executor even if the model emits them.
5. **Provenance enforcement.** GitHub issues created via mail are always prefixed `Filed on behalf of <principal> via email.` regardless of what the model returns. GitHub comments are always prefixed `> From <principal> via email:`.

The agent proposes; the code enforces. Producers propose; the code enforces.

## How it integrates with other elves

```
inbound email
  ↓ MailFetcher (IMAP, allow-list)
  ↓ MailTriager (Claude runBare → JSON action)
  ↓ MailAction.execute
      ├─ PendingStore.put(correlationId → inbound metadata)
      └─ ElfBus.enqueue(github.issue.create)  ──►  github-worker's inbox
                                                       ↓ (github-worker tick)
                                                       ◄── github.issue.create.result
  ↓ ElfBusConsumer.poll (next mail-worker tick)
  ↓ BusHandlers.IssueCreated
  ↓ SMTP reply to original sender with the issue URL
```

A peer elf can also reach the principal directly via `mail.send`:

```
peer elf decides to email principal
  ↓ FileSystemBus.enqueue(mail.send) ──►  mail-worker's inbox
                                           ↓ (mail-worker tick)
                                           ↓ BusHandlers.MailSend
                                           ↓ ALLOWED_RECIPIENTS check
                                           ↓ EmailSender.send
                                           ◄── mail.send.result (Message-Id)
```

Adding more action targets (calendar events, Slack messages, etc.) means adding a new `MailAction` variant and either a local executor or a new elf to consume the kind.

## See also

- [elf-bus-common](https://github.com/House-elves/elf-bus-common) — the message bus transport + spec
- [github-worker](https://github.com/House-elves/github-worker) — consumes the `github.issue.*` kinds

## License

Apache 2.0 — see [`LICENSE`](LICENSE).

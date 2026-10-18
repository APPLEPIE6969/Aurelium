# Aurelium - Feature Plan

New features for upcoming releases, in the order they ship.

This file is for **features only**. Bugs, money-integrity defects, dead code and severity
triage live in [AURELIUM-FIXPLAN.md](AURELIUM-FIXPLAN.md), which has its own ID scheme
(`C` critical, `H` high, `M` medium, `L` low, `D` dead code, `H11` candidate). Feature IDs
use the `F` series (`F1` through `F8`) and are **never renumbered** once assigned: a feature
that is cut from a release keeps its number and moves down, so a fix ID in a published
patch note still resolves.

Anything already tracked in the fixplan is referenced by its fix ID here rather than
restated, so there is exactly one place to change for each item.

---

## 0. Rules every feature follows

These are the same rules the fixplan enforces. A feature that cannot meet them is not ready
to ship.

1. **A test ships with the feature, and it fails on the old code.** No exceptions. For money
   features that means `MoneyInvariantTest`: `assertMoneyUnchanged`, `assertMoneyMintedBy`,
   `assertMoneyBurnedBy`. See the fixplan's `MoneyInvariantTest` harness in
   `src/test/java/com/aureleconomy/economy/`.
2. **Fix the root cause, no layered workarounds.** A feature that needs a guard rail to
   survive the old broken path means the underlying bug is still open.
3. **No refactoring beyond the feature itself.** Touch only what the feature requires.
4. **One feature per commit.** Separable changes split into separate commits, each green.
5. **New config keys go through `upgradeConfig()`**, and `config-version` is bumped in the
   same commit. Never conflate `config-version` with the database schema version, they are
   independent counters.
6. **Breaking, config and database changes appear in both docs**: `changelog.md` written for
   players (no IDs, no filenames) and `patchnotes.md` written for developers (file by file,
   tagged `*(fixes <ID>)*`). Behaviour changes get a `Breaking Changes` heading in
   `patchnotes.md`. Do not edit sections for releases already published.
7. **New CI or contract guards are mutation-verified**, same as tests. Mutate only the target
   line; a full-block replacement duplicates the original and produces `TooManyActualInvocations`
   noise instead of a signal.
8. **Empty or rate-limited API responses are UNKNOWN**, never "0 failures". An unauthenticated
   GitHub API call returning 60 requests an hour will hand you an empty job list that reads
   exactly like a clean run. Use `gh`, which is authenticated.
9. **Publish only on explicit sign-off.** Release actions move on a checklist confirmation,
   never on an ambiguous message.

---

## 1. v1.5.5

**Status: gated.** v1.5.5 is not feature-led. Its release gate is the whole of the fixplan's
execution order, batches A through E (steps 1 to 12), plus the v1.5.5 documentation pass over
everything that landed. Nothing here ships ahead of that gate.

Features landing in v1.5.5, in build order:

| Order | ID | Feature | Ships with | Depends on |
|-------|----|---------|-----------|------------|
| 5 | F1 | Transaction audit ledger | Batch B | F4 (shared migration v7) |
| 8 | F3 | Dashboard API rate limiting and bounded request bodies | Batch C | none |
| 9 | F4 | Money columns `DOUBLE` to `DECIMAL(18,2)`, migration v7 | Batch D | H5 |
| 11 | F2 | Mobile bulk-buy (Floodgate phone detection) | Batch E | money core fixed and tested |
| 12 | F5 | Auction house pagination | Batch E | M1 |
| 12 | F6 | Admin price reset and rollback command | Batch E | F1 (before/after evidence) |
| 12 | F7 | Idempotency keys for cross-thread money operations | Batch E | H9, H10 |
| 12 | F8 | Wire up `messages.yml` as the real message source | Batch E | config migration framework |

Full specifications, with the reason each one should be added, are in
[AURELIUM-FIXPLAN.md section 7](AURELIUM-FIXPLAN.md) under the matching `F` heading. That text
is the spec of record; this file tracks only placement and status.

### Notes that carry across releases

- **F1 is the keystone.** Every critical money bug found so far (C1 to C5, H4, H6, H10) printed
  money, and there is currently no way to find out how much was minted or by whom. Fixing the
  exploits does not recover the damage. The ledger makes every later money bug observable
  instead of theoretical, and F6 depends on it for evidence.
- **F1 and F4 bundle into a single migration v7.** One migration, one test pass. Do not split
  them; splitting means two schema bumps and two chances to get the ledger's own writes wrong.
- **F2 is the largest single item.** It needs a new CI workflow
  (`.github/workflows/bedrock-mobile-e2e.yml`) and a new bot script (`ci/bedrock-bot.js` using
  `bedrock-protocol`), because the feature cannot be proven by a unit test alone. Floodgate
  integration must be optional and isolated behind one wrapper, following the pattern the
  scanner already uses for ItemsAdder, Oraxen and MMOItems. The plugin has to run unchanged
  with neither Floodgate nor Geyser installed.
- **F2 must reuse the existing bulk purchase path**, not write a second one. The handlers in
  `ShopGUI` and `MarketGUI` already do bulk trades on shift-click; passing a flag through them
  is what makes the price re-check, the inventory space check and every money fix apply to the
  mobile route automatically.
- **F4 changes stored money representation.** This is a `Breaking Changes` entry in
  `patchnotes.md` with upgrade instructions, and it needs a migration test that asserts balances
  survive the `DOUBLE` to `DECIMAL(18,2)` conversion without drift.

### CI runner pinned during the ubuntu-latest migration

Done ahead of v1.5.5 rather than inside it, and recorded here because the release gate is where
a moving runner label would have done damage.

All ten jobs in `.github/workflows/build.yml` were on `runs-on: ubuntu-latest`. GitHub is rolling
that label from Ubuntu 24.04 to 26.04 between **19 October and 19 November 2026**
(`actions/runner-images#14748`), gradually, so during the window `ubuntu-latest` means different
things on different days and two runs a week apart can sit on different Ubuntu versions.

All ten are now pinned to `ubuntu-24.04`, with a comment at the top of the workflow explaining why
so the pin does not get "helpfully" reverted.

What the migration actually threatened here was **Python, not Java**. The JDK is pinned by
`setup-java` in all ten jobs, so the image's default Java never mattered. The nine contract checks
run under an unpinned `python3` across thirteen call sites and inherit whatever the image ships.

**When to move to `ubuntu-26.04`:** deliberately, after a green run on it. Not when the label moves.
Worth knowing at that point that the announcement's own version table is wrong in places, listing
Java 17 as the 26.04 default when the image ships 25, and omitting the Docker 28 to 29 bump.

This is the same failure shape as the subset-runner false green: a run that reports on something
other than what it appears to report. Worth keeping in mind when reading any red during the
migration window.

### Carried into v1.5.6

Nothing yet. The fixplan's `H11` (a single corrupt `auctions` row hard-crashing boot) is a
recorded candidate, not scheduled work, and does not belong in v1.5.5.

---

## 2. v1.5.6

**Status: open. F9, F10, F11, F15 and M15 to M19 assigned, more to come.**

### F9. Discord alerts and read-only commands

#### Problem it solves

A server owner running Aurelium has no way to find out that their economy is broken while they
are away. The plugin currently answers three questions badly, and all three are answered only
by someone tailing a console:

1. **It stopped working.** If the database is unreachable or a migration fails at boot, the
   plugin does not enable and the only evidence is a stack trace the owner will not see until
   a player complains.
2. **Money is being printed.** Every critical bug found so far (C1 to C5, H4, H6, H10) had the
   same shape: something minted money with no matching sink. None of them left a trace a
   server owner could read.
3. **The market has run away.** Dynamic pricing has no safety net. `MarketManager` does detect a
   crash (`checkPriceAlert`, `MarketManager.java:183`) and broadcasts it to players in game, but
   it only fires downward, it resets silently, and telling every player on the server about a
   price move is the wrong audience for an operational alert.

Discord is where a server owner already is, and it alerts when they are not at the console.

#### What ships

A Discord integration with two halves: the plugin pushes alerts to a channel, and optionally
the bot answers read-only questions from Discord. Both halves are independently useful; the
second is optional and can be left off.

##### Alert categories

Every category is individually switchable, so an owner who only wants crash alerts is not forced
to take price spam.

| # | Category | Fires when | Why it matters |
|---|----------|-----------|----------------|
| 1 | Enable failure | `initialize()` returns false | The plugin is down and the owner does not know yet. The single highest value alert. |
| 2 | Uncaught error | Throwable reaching the plugin's top-level handler | With stack trace excerpt and the thread name. |
| 3 | Migration failure | `runMigrations` rolls back | Silent partial schema otherwise. |
| 4 | Database lost and recovered | Connection failure then success, with downtime | H1's `DatabaseConcurrencyTest` makes this a real event with a start and an end, so duration is meaningful. |
| 5 | **Net minting above threshold** | Money created without a matching sink, above a configured amount, over a window | The exploit class, directly. Only computable once F1's ledger exists. This is the reason to build F9 after F1 rather than before. |
| 6 | Large single credit or debit | Admin give or take, `/pay`, above a threshold | Includes the actor, because an admin action is the one a player will dispute. |
| 7 | Money operation refused | `withdraw` / `withdrawIfSufficient` returns 0 rows where success was expected | C3-core made these return the real row count, so they are detectable for the first time. |
| 8 | Money supply out of band | Total currency in circulation above or below a configured range | The economy's solvency check, the one number an economy owner actually cares about. |
| 9 | Watchlist balance crossed | A watched player's balance moves past a threshold | "Is my friend still rich" without opening the game. |
| 10 | Price crash | Extends the existing `checkPriceAlert` downward path to Discord, alongside the player broadcast | Already computed; this just routes it to the right audience. |
| 11 | **Price spike** | Price rises past a threshold | New. Nothing detects this today. A spike is often an exploit or a mispriced scan result. |
| 12 | Price recovered | Price returns past the reset threshold | New. The reset at `MarketManager.java:217` is currently silent, so an owner watching a crash never learns it ended. |
| 13 | Floor or ceiling engaged | A clamp at `MarketManager.java:166-169` fires, with a count of consecutive clamped transactions | A price pinned at the ceiling means the clamp stopped protecting the economy and started distorting it. This is the early warning for F6. |
| 14 | Spread anomaly | Buy and sell prices diverge past a threshold | A sign that pricing has desynced, often after a config edit. |
| 15 | Auction sold far from asking | Final price under a percentage of ask | Either a bargain deal or someone farming the bid path. |
| 16 | Bid burst | N bids on one item within M minutes | Exploit or attention, worth knowing either way. |
| 17 | Listing spam | One player lists N items within M minutes | Cheap to automate, expensive to clean up. |
| 18 | Order filled off-market | A buy order filled at a price far from the market | H6 territory. |
| 19 | Scanner discovered a new item | A new custom item enters the market from ItemsAdder, Oraxen or MMOItems | It now needs a price decision it does not have one. |
| 20 | Stock below threshold | A watched item's supply falls under a configured level | The one "stocks" alert, which is the case the owner asked for. |
| 21 | Dashboard purchase | A web or cloud purchase above a threshold, or from a new IP | The dashboard is an authenticated path that bypasses in-game checks. |
| 22 | Purchase replay blocked | The dedup path fires | H9's defence doing its job, which is worth confirming. |
| 23 | Daily digest | Revenue, top traded items, net minting, largest single transaction | Scheduled. Turns a firehose into one readable message. |

###### What this cannot do

Discord cannot report a hard JVM crash, an OOM kill, or a server someone stopped with `kill`.
The process is gone before any Java code runs. Categories 1 to 4 cover the plugin failing and
recovering, which is the part Aurelium is responsible for. A true process watchdog is a
different tool and is out of scope.

###### Two-way, optional

**Deferred to v1.5.6's successor, tracked as F9b.** Off by default and not part of v1.5.6. Only
meaningful with a bot token rather than a webhook, and the alert half is worth a production cycle
on its own before anything is built on top of it. See F9b in the v1.5.7 section.

- Read-only slash commands: `/aurelium balance <player>`, `/aurelium price <item>`,
  `/aurelium supply <item>`, `/aurelium whoami`. Read-only is a hard boundary, not a
  convention. An economy plugin that accepts money-moving commands from a chat platform is a
  much larger risk than one that does not.
- A "roll back prices" button on the alert from category 13, calling the same code path as F6's
  `/eco pricerestore`, behind an explicit permission and a confirmation. This is the natural
  pairing: F6 builds the recovery mechanism, Discord puts the button in front of the person who
  needs it at 2am.

#### Engineering requirements

These are not optional polish. Each one is a way this feature breaks something that currently
works.

1. **Never block on HTTP.** A Discord call must never sit on the main thread, and never inside a
   database write or the write lock. Posts go through a bounded queue on a dedicated executor.
   If Discord is down, the plugin must behave exactly as if the integration were not configured.
2. **Alert-storm protection.** Discord rate-limits webhooks, roughly 30 requests per 10 seconds
   each. A runaway price change or a mass auction event would fire hundreds of alerts, get
   rate-limited, and lose the alerts at exactly the moment they matter. Requires a per-category
   token bucket, per-key cooldowns following the existing `alertedItems` pattern at
   `MarketManager.java:199-219`, and queue overflow that coalesces and says so rather than
   dropping silently.
3. **Integration failure must be visible.** If the integration is broken, the console says so
   once and then backs off. An owner who disables alerting by accident must find out from a
   log line, not from weeks of silence.
4. **Replay after downtime.** If Discord was unreachable during an incident, replay the last N
   buffered alerts when it returns. Losing the crash alert to a network blip is the failure mode
   that would make this feature untrustworthy.
5. **Testable with no network and no token.** CI has no Discord credentials and must not need
   any. The HTTP client is injected; every test runs against a fake.

#### Security: where the credential lives

**Recommendation, and the reason behind it.** A Discord bot token is a live credential for the
whole bot, not just one channel: it can read DMs, join servers, and act anywhere the bot has
access. A webhook URL is strictly weaker, scoped to a single channel and a single server, and
is revoked by deleting it. Both are secrets.

So neither goes in `config.yml`, and the owner should know why before picking:

- `config.yml` is the file server owners paste into forums and support channels when asking for
  help. It is the most widely leaked file a Minecraft plugin has. A token in it will eventually
  be public.
- Both credentials belong in a dedicated `discord.yml` (or `secrets.json`) in the plugin data
  folder, gitignored, following the same discipline this repo already applies to `.env`,
  `*.pem` and `*.key`. `config.yml` holds only `discord.enabled` and channel routing.
- The token is never logged, never included in diagnostics, and masked in any output that shows
  configuration.
- **The credential must also be deliverable by environment variable**
  (`AURELIUM_DISCORD_WEBHOOK_URL`, `AURELIUM_DISCORD_TOKEN`), because a managed panel hands you
  environment variables and not an editable file. See F10, which owns that mechanism and the
  precedence rule.
- On Linux, warn at startup if that file is group or world readable, when a file is in use at all.
- **Webhook first.** A webhook URL covers all 23 alert categories. The bot token is only needed
  for the two-way half. The default path should be the one with the smallest blast radius.

**Open decision for the owner:** confirm the credential file and whether the two-way half is in
v1.5.6 or deferred. The alert half does not need the answer to start.

#### Per-feature detail

- **Config keys** (added via `upgradeConfig()`, with the `config-version` bump in the same
  commit): `discord.enabled`, `discord.mode` (`webhook` or `bot`), `discord.credential-file`,
  `discord.alert-channels` (category to channel mapping), `discord.rate-limit-per-category`,
  `discord.replay-buffer-size`, plus one `discord.alerts.<category>.enabled` per category 1 to 23
  and the thresholds for 5, 6, 8, 9, 11, 13, 14, 15, 16, 17, 18, 20 and 21.
- **Permissions:** `aureleconomy.discord.test` (default op), `aureleconomy.discord.pricerestore`
  (default op, gates the rollback button). Read-only slash commands need no permission because
  they expose what an admin can already see, and should still respect the viewer's Discord
  permissions.
- **Database:** no schema change for the alert half. Categories 5 and 8 read F1's ledger. The
  daily digest needs no table, it is computed on demand. If replay buffering is persisted rather
  than in memory, that is a v7 follow-on migration and must be decided before implementation,
  not after.
- **Tests:**
  1. `DiscordNotifierTest` with a fake HTTP client: each of the 23 categories fires exactly when
     its condition holds and not otherwise. The spike and recovery cases have no equivalent in
     the tree today and must be proven against a real `MarketManager` price sequence.
  2. Rate limiting: burst 200 crash alerts for one item, assert the number of messages actually
     sent is within the budget, assert the cooldown suppresses the rest, and assert the overflow
     is logged rather than silently dropped.
  3. Blocking: assert a Discord client that never returns does not delay a balance write. This
     test is the one that keeps requirement 1 honest.
  4. Credential handling: assert the token appears in no log line, no exception message, and no
     diagnostic output. Assert `config.yml` contains no secret value after a full enable cycle.
  5. `/eco discord test` sends exactly one message and reports success or the failure reason.
  6. CI runs with no credential present and asserts the plugin enables and operates normally
     with alerting off.
- **Docs:** `changelog.md` gets a player-free, owner-facing section describing the categories in
  plain terms. `patchnotes.md` gets the file-by-file entry tagged `*(adds F9)*`, plus a
  `Breaking Changes` entry for the new `config-version` and for any category that changes what
  gets logged at startup (the world-readable credential warning is one).
- **Depends on:** **F1**, required, for categories 5 and 8. **F6**, for the rollback button.
  **H1 commit 2**, for category 4 having a real recovery moment. `MarketManager.checkPriceAlert`
  for 10, 11, 12 and 13.
- **Risk:** highest of any feature so far, because it adds a network dependency to a plugin that
  currently has none, and a credential store to a plugin that currently keeps secrets nowhere.
  The blast radius of getting requirement 1 wrong is the entire economy stalling on a Discord
  timeout. Mitigation: the integration is off by default in v1.5.6 so existing servers are
  unaffected, and the queue is bounded with an explicit drop-and-log policy.

##### Ship shape

One concern per commit, matching the house rules. Suggested order:

1. `DiscordNotifier` skeleton, config, credential file, fake-HTTP tests, nothing wired to a
   trigger yet. `/eco discord test` lands here.
2. Availability alerts (1 to 4).
3. Price alerts (10 to 14), extending `checkPriceAlert` rather than duplicating it.
4. Rate limiting, replay buffer, blocking proof.
5. Ledger-dependent alerts (5, 7, 8) once F1 is live.

The two-way half is **not** in this list. It ships as F9b in v1.5.7.

### F10. Discord integration runs on any host

#### Problem it solves

The Discord integration has to work on a home PC, a VPS, a Docker container, and a managed
panel like FalixNodes or Pterodactyl, without the owner editing a file they cannot reach.

It can, and the reason is directional. **Discord integration is outbound only.** The plugin
opens no listening socket, makes HTTPS requests to `discord.com`, and receives nothing inbound.
Outbound HTTPS works on essentially any host, including panels that route nothing toward you at
all.

#### Scope decision, on the record

**The web dashboard is not being made portable.** Owner decision, and F10 is scoped to the
Discord integration only.

For the avoidance of doubt about why: the dashboard is **inbound**. It binds a port and expects
something to route to it. `WebServer.java:56` hardcodes `web.local.port` to 8585 and
`WebServer.java:57` defaults the bind to `localhost`, so on a panel it would need a
platform-assigned port, an explicit opt-out of loopback binding, and a TLS-terminating reverse
proxy in front, because the session token travels in the URL (`WebServer.java:91-92`). That is a
separate project with its own threat model, and the comment at `WebServer.java:67-74` records
why the bind default must not be loosened to make it convenient.

No `PORT` key, no `WEB_HOST` key, no bind changes. Nothing in F9 or F10 depends on the
dashboard, and the Discord integration must work with the dashboard disabled or failed.

#### What ships

Everything below is about the outbound path only.

| # | Item | Detail |
|---|------|--------|
| 1 | **Credential by environment variable** | `AURELIUM_DISCORD_WEBHOOK_URL` and `AURELIUM_DISCORD_TOKEN`. A panel hands you environment variables in a UI, not an editable file. This is the single requirement that makes F9 work on FalixNodes at all. |
| 2 | Precedence rule | Environment variable wins over file, because env is the channel the operator controls at deploy time. Log which source was used, never the value. |
| 3 | Env-only mode | The plugin must not need a writable data folder to alert. If the credential file is absent or the folder is read-only, env alone is sufficient and alerting still works. |
| 4 | No inbound socket | Assert in a test that enabling Discord opens no listener. This is the property that makes the feature host-independent, so it gets protected rather than assumed. |
| 5 | Works when the dashboard is down | Alerts do not depend on `WebServer` having started, on the cloud registration having succeeded, or on any other subsystem. |
| 6 | In-memory state only | The rate limiter and the replay buffer are in-memory. Neither may require disk, since a panel's plugin folder may be ephemeral or read-only. |
| 7 | One thread | The HTTP client uses a single daemon thread and a bounded queue. A panel container may have 512 MB and a CPU share, and the integration must not be what exhausts it. |
| 8 | Egress diagnostics | If `discord.com` is unreachable, say so once with the likely cause and the panel setting that opens outbound HTTPS, then back off. Silence here means the owner believes they are covered and are not. |
| 9 | IPv4 preference | Some containers advertise IPv6 without routing it, where an outbound request tries IPv6 first and stalls until timeout. The client prefers IPv4 and this is recorded as a deliberate choice, not left to the container's resolver. |
| 10 | Per-platform docs | Exact setup for FalixNodes, Pterodactyl, Docker and a plain VPS. |

#### Honest limits

- **TLS trust** comes from the JVM's own trust store, not the container's CA bundle, so this
  should work. It gets a test rather than an assumption.
- **A panel may block outbound HTTPS entirely.** Nothing in the plugin can work around that, and
  item 8 exists so the failure is legible instead of silent.
- **An ephemeral plugin folder loses the SQLite database on restart.** That is the operator's
  volume configuration, not the plugin's, and it is a separate concern from alerting. The
  Discord integration is unaffected, which is the point of items 3 and 6.

#### Per-feature detail

- **Config keys:** none new. The credential stays in `discord.yml` or the environment, and
  `config.yml` holds only `discord.enabled`. **No `config-version` bump.**
- **Permissions:** none.
- **Database:** no schema change.
- **Tests:**
  1. Precedence: env beats file, file beats unset, for both credentials.
  2. No credential anywhere: the plugin enables and operates normally, alerting reports off.
     This is the CI-without-secrets case and it must be green on every platform build.
  3. Read-only data folder: the plugin enables and alerting works from env alone.
  4. Enabling Discord opens no listening socket.
  5. Alerting still functions with `WebServer` failed to start.
  6. Unreachable Discord endpoint produces exactly one diagnostic, then backs off.
  7. The credential value never reaches a log line, in either the file or the env path.
- **Docs:** `patchnotes.md` gets a per-platform setup section tagged `*(adds F10)*`, with the
  `AURELIUM_DISCORD_*` names in a form an owner can copy. `README.md` gets the same table.
- **Depends on:** nothing. F9 consumes this, so they ship together, but F10 has no prerequisite.
- **Risk:** low. It adds no listener, changes no default, and adds no config key. The failure
  mode is that alerting silently does not work, which items 2, 8 and tests 2 and 6 exist to
  prevent.

### Owner-reported Aurelium Cloud defects (v1.5.6)

Reported by the owner against Aurelium Cloud, `https://aurelium.alwaysdata.net`, on 2026-10-18:
stocks filter buttons do not filter or are very slow, the market is briefly empty on refresh,
and the market category view does not show all of a category's items.

**These are bugs, not features**, and they are listed here because the owner assigned them to
v1.5.6. They belong in `AURELIUM-FIXPLAN.md` so they carry IDs and the usual mandatory tests.
Proposed IDs **M15 to M18**, free because the fixplan's M series ends at M14.

**Where the code is.** The cloud is a separate repository at `D:\aurelium-cloud`: a Rust backend
(`src/api.rs`, `src/store.rs`) serving `web/`. Most of these land there, not in `D:\Aurelium-plugin`.
The frontend is shared, so those parts land in both.

**A correction worth keeping.** The first attempt at this section diagnosed
`src/main/resources/web/app.js` and `DashboardApiHandler.java` in the plugin. That was the
**local** dashboard, served from the plugin jar through the in-game `/web` command. It is a
different application from the cloud. The saved part of that mistake is useful though: the two
frontends are **byte-identical** (SHA256 matches on `app.js`, `index.html` and `style.css`), so
frontend findings transfer between the two, while backend findings do not.

#### The underlying problem behind M15 and M18

The plugin and the cloud each implement the same dashboard API separately. The local one uses
`CollectionPager`, which documents search, sort and paging with a per-collection `Spec`
allowlist (`CollectionPager.java:26-39`). The cloud one is `paged()` in `src/api.rs:803-843`,
which reads **only** `page`, `size` and `q`.

The frontend is written against the local contract and sends `sort` and `dir` on stocks,
auctions and orders (`app.js:945`, `1030-1031`, `1172-1173`). The cloud silently ignores both.
Two implementations, one contract, and nothing asserting they agree. That single gap produces
M15 and M18.

| ID | Symptom | Where it lives |
|----|---------|----------------|
| M15 | Sort and filter controls do nothing | Cloud, `src/api.rs:803-843` |
| M16 | Stocks page is slow | Shared frontend. Lands in both repos |
| M17 | Market briefly empty on refresh | Cloud, `src/store.rs:277` with `src/api.rs` |
| M18 | No page controls on any list page | Cloud, `src/api.rs:836-843` |
| M19 | Bid input is a stock number field and mislabels the price | Frontend, duplicated. Plus a local-only truncation in `DashboardApiHandler.java:571` |

#### M15. Sort and filter controls do nothing on the cloud

`paged()` parses `page`, `size` and `q`. It never reads `sort` or `dir`. Every sortable column
header and every stat-tile sort on the cloud stocks page, plus the auction and order sorts, send
parameters that are dropped on the floor. Nothing errors, the table simply never reorders, which
reads to a user as a broken button.

Two smaller faults sit behind it in the shared frontend:

- `filteredStocks()` (`app.js:1196-1197`) returns its input unchanged, under a comment claiming
  rows arrive pre-filtered. It is a no-op under a name that promises otherwise.
- The stocks page has no filter chips at all (`index.html:192-241`), while orders has a chip row
  at `index.html:179-183` and auctions builds one at `app.js:909-929`. So the affordance the
  other pages have is simply missing here.

What ships: `sort` and `dir` honoured in `paged()`, with the same allowlist idea as the local
`Spec` so a client cannot sort on a field that is not there. `filteredStocks` either filters
properly or is deleted rather than left as a no-op with a misleading name. And a decision on
whether stocks gets a real chip row.

#### M16. The stocks page is slow

`app.js:1182-1183` awaits the price-history fetch **serially after** the stocks fetch, and
`renderStocks()` is not called until it resolves:

```js
priceHistory = await loadPriceHistory().catch(() => ({}));
renderStocks();
```

The rows are in memory the whole time the table waits on a second round trip that only draws
sparklines. On the cloud this compounds with the page polling every 30 seconds
(`src/api.rs:667`), so each cycle pays the serial pair.

What ships: render as soon as the stocks response lands, then fill sparklines in place. Also check
whether the stocks search debounces, since `q` is a server round trip per request
(`app.js:1174`) and an undebounced box makes the same page slower still.

#### M17. The market is briefly empty on refresh

`store.rs:277` documents the behaviour: the latest snapshot for a server, **or an empty object
if it has never synced**. `snapshot_items` then reads `snapshot.get("items")`, finds nothing, and
returns an empty page. The frontend renders "No items found" (`app.js:811`) and has no way to
tell that apart from a server which genuinely has nothing for sale.

The data to fix this already exists and is simply not surfaced: `store.rs:81` has a `last_sync`
column, updated at `store.rs:271` and `296`.

What ships: expose whether the snapshot exists and when it was last pushed, and have the frontend
render a loading or not-yet-synced state rather than an empty market. A server that has never
synced and a server with nothing for sale are different states and must not share a code path.

#### M18. No page controls on any list page

The envelope key names do not match. `paged()` emits (`src/api.rs:836-843`):

```json
{ "items": [...], "page": 0, "size": 25, "total": 137, "totalPages": 6, "stats": {...} }
```

`renderPagination` reads (`app.js:846-849`):

```js
const totalPages = data?.totalPages ?? 1;
const totalItems = data?.totalItems ?? 0;   // cloud sends "total"
const pageSize   = data?.pageSize   ?? 0;   // cloud sends "size"
const page       = data?.page       ?? 0;
```

Both mismatched fields fall back to `0`, and the first guard is
`if (totalItems === 0) { box.innerHTML = ''; return; }` (`app.js:851-853`). **The pagination bar
is never rendered**, so only the first 25 rows are reachable and the rest of a category is
invisible. That is the reported symptom.

Worth noting the attempts to supply the total already exist and are also in the wrong place: all
four call sites nest it inside `stats` (`src/api.rs:888` `totalItems`, `906` `totalAuctions`,
`943` `totalOrders`, `961` `totalStocks`), where the frontend does not look.

**This is not only the market.** All four list endpoints are affected: items, auctions, orders
and stocks all lose their pagination on the cloud. It was reported on the market because that is
where a player notices. Even with the guard removed, the readout would be wrong, since
`pageSize = 0` makes `from` and `to` collapse (`app.js:856-857`).

What ships: one agreed envelope. Either the cloud emits `totalItems` and `pageSize`, or the
frontend accepts both spellings. Prefer changing the cloud, because the frontend already agrees
with the local backend and the local backend works.

#### M19. The dashboard bid input is a stock number field, and it mislabels the price

Owner-reported on the cloud website. Same file as M15 and M16, so it ships with them.

The bid modal is a bare control (`index.html:324`):

```html
<input type="number" id="bid-amount-input" value="1" min="0.01" step="0.01">
```

No stepper, no market context, no quantity awareness. The in-game `BidGUI` has +10%, +50% and
+100% buttons; the website has a text box. That is the gap the owner reported.

Two correctness bugs sit in the same component and are the reason this cannot wait for the UI work:

**The label is wrong half the time.** `index.html:323` reads **"Your Bid (per item)"**
unconditionally. Price is per unit only in UNIT mode; in STACK mode it is the whole stack
(`AuctionItem.java:112-115`). The payload already carries `purchaseMode` and the auctions card
reads it (`app.js:976`), so the information is present and the modal discards it. A buyer and a
seller can end up disagreeing about what was agreed, which is the same trap F11 flags on the
selling side.

**There is no minimum bid check in the browser.** `min="0.01"` permits 0.01 against a current bid
of 5000, and `confirmBid` (`app.js:1822`) posts whatever is typed without validating. The in-game
path does validate, at `BidGUI.java:120`, and rejects anything not strictly above the current
price. To be accurate about the severity: this is a **UX gap, not a money hole**, because
`WebPurchaseExecutor.java:342` rejects the purchase server-side with "Bid too low". The player
just finds out after a round trip instead of before submitting.

And the default is naive: `app.js:1808` computes `price + 1`. A hardcoded +1 regardless of
magnitude, so on a 100,000 auction it pre-fills a bid that loses instantly, and on a 0.50 auction
it pre-fills a 200% jump.

**One further defect, and it is local-only.** `DashboardApiHandler.java:571` truncates the bid:

```java
// Fractional bids are meaningless for the in-game BidGUI.
amount = (int) bidAmount;
```

The comment is wrong. The in-game `BidGUI.java:119` does `.setScale(2, HALF_UP)` and
`WebPurchaseExecutor.java:323` does the same, so cents are meaningful everywhere except in this
one line. Entering `100.50` on the **local** dashboard silently places a bid of 100. The cloud
takes a different route (`player_action` in `api.rs:694`, one of five actions it shares) and does
not truncate.

What ships:

1. The label and any per-unit wording follow `purchaseMode`, and the modal shows the quantity the
   buyer is bidding on in UNIT mode.
2. Client-side minimum bid validation matching `BidGUI`'s rule. The server stays authoritative;
   this only saves the round trip.
3. A pre-fill derived from the auction rather than a fixed +1: the minimum acceptable bid, or a
   configurable increment.
4. The proper input UI: stepper buttons matching the in-game multipliers, current bid and your
   balance visible, so a bidder can tell whether they can afford it before submitting.
5. The `DashboardApiHandler.java:571` truncation removed, and the stale comment deleted rather
   than reworded, since it is what made the line look deliberate.

**Repository split, which matters here.** Items 1 to 4 are frontend, and the frontend is
byte-identical in `D:\aurelium-cloud\web` and `D:\Aurelium-plugin\src\main\resources\web`, so
they land in **both**. Item 5 is `DashboardApiHandler.java`, which is local only.

Non-negotiable: this must not become a second implementation of bid validation that disagrees
with the plugin. The in-game rule at `BidGUI.java:120` and the cloud rule have to say the same
thing, which is the same drift that produced M15 and M18.

Tests: STACK mode shows no per-unit wording and UNIT mode does; a bid at or below the current
price is rejected client-side; the pre-fill is a valid bid; the modal renders both modes; and a
fractional amount survives the local handler without truncation. The last one fails on the current
tree.

Docs: `changelog.md` per fix, `patchnotes.md` tagged `*(fixes M19)*`. No Breaking Changes heading.

Depends on: M7 for the web scope. Note `WebPurchaseExecutor.java:328` routes the bid through
`purchaseUnits`, which is H2's off-thread inventory mutation, landing in v1.5.5.

Risk: low. It touches no money path, because the server remains authoritative for the actual
charge. C1, the auction bid charging bug, is v1.5.5 and is separate from all of this.

#### The fix that stops this recurring

A shared contract test asserting the envelope keys, run against both implementations. A test per
implementation asserting "my own output is what I expect" passes on both today, which is exactly
how the drift survived. The test has to pin the names the **frontend** reads, because the
frontend is the consumer both were written against.

Worth deciding separately whether the two backend implementations should stay separate at all.
Two `page` functions with different key names is a recurring source, not a one-off.

#### Also on record

The frontend is **duplicated byte-for-byte** across `D:\Aurelium-plugin/src/main/resources/web`
and `D:\aurelium-cloud/web`. Every frontend fix in this section must be applied twice, and
nothing currently enforces that they stay identical. A build step that copies one to the other,
or a checksum check in CI, is cheaper than discovering the drift in production.

#### Per-defect detail

- **Config keys:** none. These are code fixes, not settings. No `config-version` bump.
- **Permissions:** none.
- **Database:** no schema change. `last_sync` already exists.
- **Repository:** M15, M16, M17 and M18 all land in `D:\aurelium-cloud`, except the frontend
  part of M16 and the `filteredStocks` part of M15, which must also be applied in
  `D:\Aurelium-plugin` so the two copies stay identical.
- **Docs:** `changelog.md` gets a player-facing line per fix, no IDs and no filenames.
  `patchnotes.md` gets the file-by-file entries tagged `*(fixes M15)*` through `*(fixes M18)*`,
  and notes that the frontend change is duplicated across the plugin jar and the cloud host. No
  `Breaking Changes` heading, since nothing an operator configures changes.
- **Depends on:** M7 for the web scope, which is the same surface.
- **Risk:** low. None of these touch money, which is the standing bar in this repo. M18 is the one
  that changes visible behaviour the most, because every list page on the cloud starts paginating
  correctly at once and page counts that were previously invisible become reachable.

#### Tests

1. **Envelope contract, both sides.** A shared fixture asserting the keys the frontend reads are
   present on every list endpoint. Written against `renderPagination`'s actual reads, not against
   each implementation's own idea of the contract. Fails on the cloud today for M18.
2. **M15** sorting is applied: request the same endpoint with `sort` and `dir` and assert the
   returned order changes. Fails on the current cloud, which returns the same order regardless.
3. **M15** an unknown or disallowed sort field is ignored rather than honoured, matching the local
   `Spec` allowlist.
4. **M16** proven by timing, not by inspection: with the price-history response delayed past the
   stocks response, the table must already be painted. A test that only asserts both promises
   eventually resolve passes against the broken code and proves nothing.
5. **M17** a server that has never synced renders the not-synced state; a server synced with an
   empty market renders the empty state. Both asserted, because conflating them is the bug.
6. **M18** with more than one page of results, the pagination control is present and reports the
   correct range. Asserted on items, auctions, orders and stocks, not just the market.
7. A check that the two frontend copies remain byte-identical, so M15 and M16 cannot be fixed in
   one place and missed in the other.

### F11. Auction listing GUI with per-listing mode, duration and currency

#### Problem it solves

A seller cannot choose what they are listing, how they are selling it, or for how long.

`/ah sell <price> [duration] [currency]` takes positional arguments. The GUI is not much
better: clicking sell in `AuctionGUI` calls `handleSellPrompt` (`AuctionGUI.java:251`), which
**closes the inventory and asks the player to type the price in chat**, then hardcodes the
duration to one week (`WEEK_MILLIS`, L290), hardcodes the currency to the default (L289), and
reads the purchase mode from the server-wide config (L302-303).

So there are four separate gaps: price cannot be entered without leaving the GUI, duration
cannot be chosen at all, currency cannot be chosen at all, and stack-versus-unit cannot be
chosen by **anyone**.

That last one is not a missing option, it is a missing capability. `AuctionCommand.java:171-172`
and `AuctionGUI.java:302-303` both read the same single key, `auction-house.purchase-mode`, so
every seller on the server is locked to whatever the admin picked. No seller can pick per
listing, and the admin cannot allow one seller to sell per unit and another to sell the stack.

#### Why this is cheaper than it looks, and that is the design

The data model already does everything. `purchase_mode` is a column on `auctions` (added in
migration v1, default `STACK`), `AuctionItem.PurchaseMode` already has `STACK` and `UNIT`,
`getPricePerUnit()` and `getAvailableQuantity()` already implement the two different semantics,
and `AuctionManager.listAuction` already accepts `durationMillis` and `purchaseMode` as
parameters (`AuctionManager.java:114`).

**This is an input problem, not a schema problem.** No migration is needed to make the mode
per-listing.

The design decision that follows: `auction-house.purchase-mode` stops being an override and
becomes the initial state of the GUI toggle, which a seller can flip. Existing rows keep
whatever mode they were listed with.

One bug falls out of this for free: the GUI hardcodes `WEEK_MILLIS` while
`auction-house.default-duration` already exists and is read by the command
(`AuctionCommand.java:126`). The GUI should honour the existing key rather than adding a new
one.

#### What ships

A listing flow that never leaves the inventory.

| # | Element | Detail |
|---|---------|--------|
| 1 | Price stepper | Arrows adjust the price in configurable increments, anchored on the current market price for the held item so the common case is one or two clicks. |
| 2 | Exact price entry | An anvil-based entry for a precise figure, replacing the chat prompt entirely. A stepper alone cannot express every price a seller wants. |
| 3 | **Stack or unit toggle** | Explicit toggle item showing the current choice and its meaning. Pre-selected from `auction-house.purchase-mode`, changeable per listing. |
| 4 | Duration selector | Preset buttons, defaulting to `auction-house.default-duration`, respecting the existing maximum of one year and the semantics of `parseDuration`. |
| 5 | Currency selector | Only rendered when more than one currency is configured. Defaults to the economy default. |
| 6 | Quantity selector | UNIT mode only: how many units of the held stack to offer. `getAvailableQuantity()` already models this. |
| 7 | Live fee and affordability | The listing fee recalculates as price or duration changes, and the fee affordability check updates live rather than on confirm. |
| 8 | Confirm and cancel | A final summary showing item, price, interpretation, duration, currency, fee and total, then confirm or back out. |

#### The unit trap, called out explicitly

In STACK mode the entered price is the whole stack. In UNIT mode the same number is per unit
(`AuctionItem.java:112-115`). One field, two meanings, differing by the stack size.

The GUI must show which interpretation is active and what the buyer will actually pay, live,
before the seller confirms. This is the single most likely way this feature generates support
tickets, and it is why the confirm screen exists.

#### Non-negotiables

- **One fee helper, not two.** The fee formula is currently duplicated: `AuctionCommand.java:155-158`
  and `AuctionGUI.java:341-342`. Consolidate to one and call it from both entry points. Two
  copies of a money formula is how the command and the GUI end up quoting different fees for
  the same listing, which is the same class of defect as the v1.5.4.1 web purchase bugs.
- **Preserve the issue #33 refund path exactly.** The fee is withdrawn up front, refunded in
  full if the insert fails, and the hand is cleared only once the insert is durable
  (`AuctionGUI.java:318-325`). This was bought with a lost-item bug and must not regress.
- **Re-validate at confirm time, not at open time.** The held item, the blacklist, the
  affordability check, the mode and the currency can all change while the inventory is open,
  including the player swapping the item in their hand. Confirm re-checks all of them.
- **Bound the price.** The existing prompt does `new BigDecimal(input)` at `AuctionGUI.java:264`
  with no bound, which is H5's unbounded-allocation defect. The new entry point must go through
  the same `PriceParser` caps as every other path and must not replicate that call.
- **Withdrawal through the honest money API.** The fee withdrawal must check the result, not
  assume it (C3-core, shipped in v1.5.4.1).
- **Main thread discipline.** All inventory work stays on the main thread. The insert stays
  async through the existing `listAuction` callback, and the GUI reopens from the callback
  rather than rebuilding immediately, for the reason recorded at `AuctionGUI.java:312-314`.

#### Per-feature detail

- **Config keys:** `auction-house.price-step` (stepper increment), `auction-house.durations`
  (preset button list). Duration and currency **reuse** `auction-house.default-duration` and
  `economy.currencies` rather than adding keys. `config-version` bumps for the two new keys.
- **Permissions:** reuse the existing sell permission. One optional node,
  `aureleconomy.auction.unitmode`, for admins who want to restrict per-unit listings without
  removing them.
- **Database:** **no schema change.** `purchase_mode` already exists per listing.
- **Tests:**
  1. **Fee parity.** The same price and duration through the GUI and through the command produce
     an identical fee. This is the regression test for the duplication above.
  2. **Mode semantics.** The same price value produces the documented different totals in STACK
     and in UNIT mode, asserted against `getPricePerUnit()`.
  3. **Persistence.** A listing's mode survives a reload, asserted by round-tripping through
     `loadAuctions` rather than by reading the field back.
  4. **Backwards compatibility.** Existing listings keep their stored mode after the config key
     changes meaning.
  5. **Issue #33 regression.** A failed insert refunds the fee in full and leaves the item in
     hand.
  6. **Confirm-time re-validation.** Item swapped in hand is refused. Item blacklisted mid-flow is
     refused. Fee no longer affordable is refused. Each asserted with the flow already open.
  7. **Price bounds.** Oversized, negative and zero input are all rejected, proven by failing on
     the tree before the fix.
  8. **Duration bounds.** Over one year is rejected, matching the command's existing rule.
  9. **No chat prompt.** Assert zero `ChatPromptManager.prompt` calls across the entire GUI flow.
- **Docs:** `changelog.md` gets a player-facing section on choosing a listing mode and duration.
  `patchnotes.md` gets the file-by-file entry tagged `*(adds F11)*`, plus a
  **`Breaking Changes`** entry, because `auction-house.purchase-mode` changes from an override to
  a default. That is the one thing here that can surprise an existing admin, and it needs the
  migration note: a server relying on that key to force every listing to UNIT will after this
  change get UNIT only as the pre-selected value, still overridable by the seller.
- **Depends on:** C1 and C2 (v1.5.5, the auction money paths this GUI feeds), C3-core (landed in
  v1.5.4.1), and H4 (v1.5.5 Batch C, the UNIT-mode web buy bug). H4 matters more here than it did
  before: this feature makes UNIT mode something sellers actually choose, so the web path for
  UNIT listings gets more traffic. The ordering works out, H4 lands a release earlier.
- **Risk:** low technically, since no schema changes and the manager already takes the
  parameters. The real risk is the `purchase-mode` config semantic change, which is why it is
  called out under Breaking Changes rather than buried. The second risk is the fee formula, and
  the parity test exists specifically to close it.

#### Ship shape

1. Consolidate the fee helper and add the parity test. No user-visible change.
2. Duration and currency selectors, honouring the existing config keys.
3. Mode toggle, and the config key becomes a default rather than an override.
4. Price stepper plus anvil entry, and the chat prompt is removed from the flow.
5. Quantity selector for UNIT mode.
6. Confirm screen with re-validation.

#### More features for v1.5.6

The owner decides what else goes in v1.5.6 and states it here. Nothing is added speculatively.

When a feature is assigned, it gets an entry in this shape. Keeping the fields means nothing
gets designed on the way to shipping.

```markdown
### F<n>. <Feature name>

- **Problem it solves:** what a server owner or player cannot do today, and why it matters.
- **What ships:** the user-visible behaviour, in plain terms.
- **Config keys:** every new key, added via `upgradeConfig()`, with the `config-version` bump
  noted. Say "none" if there are none.
- **Permissions:** every new permission node with its default. Say "none".
- **Database:** migration version, or "no schema change". `DECIMAL` money columns must not be
  reintroduced outside F4.
- **Tests:** what proves it, and which of them fail on the current tree. Name the money
  invariant helpers if money moves.
- **Docs:** what goes in `changelog.md` for players and in `patchnotes.md` for developers, and
  whether a `Breaking Changes` heading is needed.
- **Depends on:** other fix or feature IDs that must land first, or "none".
- **Risk:** what could regress, and what would make this get pulled back.
```

### Sequencing rules for v1.5.6

- Anything that touches a money path waits for F1 to be live, so the change is observable in the
  ledger from the first day rather than from whenever the ledger happens to be added later.
- A feature that needs a new CI job, a new bot, or a new external dependency states that in
  **Risk** at planning time. F2 is the precedent: its Bedrock end-to-end workflow was the
  largest part of the work and was known in advance.
- Performance work needs a measurement before and after, on a stated workload. "Feels faster"
  is not a result, and the pre-H1 flakiness incident came from a change whose effect on
  concurrency was never measured.
- Do not carry a feature forward a second time. If something slips from v1.5.6, it either lands
  in v1.5.6 or it goes to a later version with the reason recorded here.

### F15. Verify CI actually runs everything

- **Problem it solves:** this repo has a documented history of reporting green on a subset, twice,
  both recorded in the fixplan. `run_checks.ps1` ran **3 of the 9** checks `build.yml` runs, so
  "static checks pass" was claimed on a subset runner, and that claim was used to sign off the
  v1.5.4.1 release gate. Separately, `check_spawner_price_key.py` passed locally and failed in CI,
  twice, for two different reasons.

The question worth asking is whether everything is genuinely tested by the GitHub workflows.
Audited on 2026-10-18, the answer is **no**, and this is what is not running.

| # | Gap | Evidence |
|---|-----|----------|
| 1 | **The four JS suites never run in CI** | `test_market.js`, `test_stepper.js`, `test_currency.js` and `test_orders.js` appear nowhere in `build.yml`. They execute only through `run_checks.ps1`, on one machine, when someone remembers. |
| 2 | **`check_textures.py` is run by nobody** | Not in `build.yml`, not in the nine in `run_checks.ps1`. Referenced by nothing in the repository. |
| 3 | **The coverage gate cannot fail** | All three version modules set `minimum = "0.0"`. `tasks.check` depends on a verification that always passes, so coverage is measured and never enforced. |
| 4 | **The contract list is hand-duplicated** | `build.yml:50` plus `57-68` and the `$contracts` array in `run_checks.ps1` each list the same nine separately, joined only by a comment asking to keep them in step. |
| 5 | **`node --check app.js` is local-only** | The same shape as gap 1: a fast syntax gate that CI never applies. |

Gap 1 is the serious one. Four suites that verify dashboard behaviour, including
`test_currency.js`, which had to be rewritten immediately before the v1.5.4.1 release because it
had gone stale, are verified on exactly one machine. The rewrite was real work and the guard
protecting it has never run automatically.

**What is already solid**, so the scope is honest rather than alarming: the Java suite runs on
all three Minecraft versions in the build matrix, and the Aurelium Cloud repo has proper CI,
running `cargo test --locked --all-targets`, `cargo clippy -- -D warnings` and `cargo fmt
--check`.

- **What ships:**
  1. The four JS suites and `node --check app.js` wired into `build.yml`.
  2. A decision on `check_textures.py`: run it or delete it. A guard nobody invokes is worse than
     no guard, because it reads as coverage in a directory listing.
  3. A coverage gate that means something. A **ratchet** rather than a number chosen today: never
     allow coverage to decrease, and raise the floor over time. Setting a hard percentage now
     would just get lowered on the first busy week.
  4. **A meta-check asserting both runners enumerate the same set of checks.** One source of truth
     rather than two lists. This closes the class of defect, not the instance. The subset-runner
     incident was fixed by hand, and nothing stops the next check being added to one list and not
     the other, which is precisely how it happened.
  5. A floor on the discovered Java test count, so a suite that silently stops discovering tests
     fails loudly instead of passing on zero. The same class as a guard that runs nothing.

- **Non-negotiables:** the meta-check must fail when a check file exists in `ci/` but appears in
  neither runner, otherwise it is just a third list to keep in step. Adding a check has to be a
  one-line change or people will not do it, and then the gap reopens.

- **Tests:** the meta-check is itself a check, so it gets a mutation test. Add a fake check file
  to `ci/` and prove CI goes red, then remove it.

- **Docs:** `patchnotes.md` only, with a `CI Changes` bullet. No `changelog.md` entry, because
  none of this is visible to a player and padding the player-facing file with CI work is how that
  file stops being read.

- **Depends on:** nothing.

- **Risk:** low, and the only way it breaks a build is by correctly failing on a gap. The
  coverage ratchet is the one that could become annoying, which is why it ratchets rather than
  jumps.

- **Placement:** this should land **first** in the v1.5.6 cycle even though it is numbered last.
  It is the cheapest item in the plan and it is what makes F9, F11 and M15 to M18 verifiable rather
  than merely claimed. It also does not change the judgement that v1.5.6 is already full, because
  this is a day of CI wiring rather than a feature.

---

## 3. v1.5.7

**Status: open. F9b, F12, F13 and F14 assigned, more to come.**

This release exists because v1.5.6 is already full, and F9 alone is a release. Splitting the
overflow forward keeps each one coherent rather than making v1.5.6 the release where everything
lands slightly broken.

All three items below are **continuations of v1.5.6**, not new directions, and every one of them
depends on v1.5.6 shipping first. Nothing here is speculative.

### F9b. Discord two-way half (deferred from F9)

- **Problem it solves:** F9's alert half is one-directional. The server can tell the owner
  something, but the owner cannot ask the server anything without opening the game or SSH.
- **What ships:** the read-only slash commands (`/aurelium balance`, `/aurelium price`,
  `/aurelium supply`, `/aurelium whoami`) and the "roll back prices" button on the alert from
  F9 category 13, calling the same code path as F6's `/eco pricerestore`.
- **Why it is deferred rather than cut:** it needs a bot token instead of a webhook, which is a
  materially larger credential for a convenience surface. A release cycle of production use on
  the alert half tells us which questions are actually asked, and the read-only command set
  should be built from that rather than guessed at now. Nothing is lost by waiting; the webhook
  path stays the recommended one for the whole of v1.5.6.
- **Precondition:** F9's alert half live in production for a full release cycle.
- **Tests:** every command is read-only, proven by asserting no money path is reachable from a
  command handler. The rollback button requires its permission and a confirmation step, and calls
  the same code F6 tested rather than reimplementing the restore. A viewer without the permission
  sees no button.
- **Docs:** `patchnotes.md` file by file, tagged `*(adds F9b)*`, plus the security note that this
  is the feature that requires a bot token and what that token can do.
- **Depends on:** F9, and F6 for the rollback path.
- **Risk:** the rollback button is the only write operation this project will expose to a chat
  platform. It goes behind a permission, a confirmation, and an audit line in the F1 ledger, or it
  does not ship.

### F12. Seller "my listings" view

- **Problem it solves:** a seller can cancel their own auction today, but only by finding it inside
  the general browse list and shift-right-clicking it (`AuctionGUI.java:162`, `:392`). There is
  no way to see what you have listed, what it is fetching, or when it expires, without paging
  through everything anyone has ever listed.
- **What ships:** a dedicated view of the viewer's own live listings showing item, price, stack or
  unit mode, time remaining, and current highest bid, with cancel from the same row. Reachable by
  command and as a tab in the existing auction GUI.
- **Why v1.5.7:** it is the natural companion to F11. Once listing is a GUI flow, there should be
  a screen showing what the flow produced. Shipping it in the same release as F11 would put two
  new GUI surfaces in one release for no reason.
- **Tests:** shows only the viewer's own listings and never another player's; cancelling removes
  the row and refunds the fee through the existing path; an empty state rather than a blank
  screen; correct after a server restart.
- **Depends on:** F11, so the mode and duration fields it displays are meaningful. F5 for the
  pagination pattern, which it reuses rather than reinvents.
- **Risk:** low. Read plus an action that already exists.

### F13. Player price watch

- **Problem it solves:** the only price alert in the plugin is a broadcast to **every player on the
  server** (`MarketManager.java:183`). That is the wrong audience and the wrong granularity. A
  player watching one item is told about every item, and a player watching nothing is told about
  everything.
- **What ships:** a per-player watchlist of item keys with a configurable threshold, notifying only
  the watching player when a price crosses it. Managed from a GUI, with a command to add and
  remove. Reuses F9's price signals for categories 10 to 13 rather than recomputing them.
- **Why v1.5.7:** it is the player-facing counterpart to the owner-facing alerts in F9, and it
  needs the rate-limiting pattern F9 establishes. Building both in one release means the spam
  protection is written once and proven under the alerting load.
- **Non-negotiables:** watch state is per player and persists, unlike the global `alertedItems`
  set at `MarketManager.java:199-219`, which is shared server-wide and must not be reused as the
  watch store. One crossing sends one notification, not one per player online. A watchlist is
  bounded in size.
- **Tests:** only the watching player is notified; unsubscribing stops it immediately; state
  survives a restart; a price crossing back and forth does not spam; a non-watching player receives
  nothing.
- **Depends on:** F9, for the price signals and the rate-limiting pattern.
- **Risk:** moderate, and it is a spam risk rather than a money risk. A price oscillating around a
  threshold with per-player state and no cooldown is exactly how a notification system becomes
  something players mute, which would then work against F9's alerts too.

### F14. Minecraft 26.3 support

- **Problem it solves:** Paper 26.3 is released. The newest stable server build is **205**
  (2026-10-17), and `io.papermc.paper:paper-api:26.3.build.205-stable` is already published on
  the Paper maven repo. Aurelium currently builds for 26.2, 26.1.2 and 1.21.11 only.

**Why v1.5.7 and not v1.5.6, which is the decision worth stating.** There is no emergency. The
shared `plugin.yml` declares `api-version: '26.1'`, and Paper treats `api-version` as a
**minimum**, so the plugin already loads on 26.2 and 26.3 today. What it does not do is get
tested or built against 26.3, so it is unsupported rather than broken. Nothing here needs to be
rescue work.

Against that, v1.5.6 already carries F9, F10, F11 and four cloud defects, and v1.5.5 has not
shipped yet. A new build target multiplies the variables in a release rather than reducing them,
and it is mechanical work that gains nothing from being rushed. It goes in v1.5.7, where it rides
along with F9b, F12 and F13.

- **What ships:** a fourth build target, `versions/26.3`, wired into the build and CI, plus a
  fourth Modrinth version entry at release.

- **The concrete steps, each verified against the current tree:**
  1. `versions/26.3/build.gradle.kts`, copied from `versions/26.2` with the version string, the
     `paper-api` coordinate above, and the shadowJar `archiveBaseName` and manifest
     `Implementation-Version` changed to `1.5.x-26.3.x`.
  2. `settings.gradle.kts`: `include("v26_3")` and its `projectDir` line.
  3. `.github/workflows/build.yml`: a matrix entry, plus whichever of the in-game, smoke and
     restart jobs pin a specific version.
  4. The full suite green on 26.3, not just a compile.
  5. A local boot on Paper 26.3 build 205, checking the console for warnings.
  6. A Modrinth version entry for the 26.3 build at release time.

- **The real cost, stated honestly:** each version's `build.gradle.kts` is roughly 130 lines of
  copied configuration with per-version divergence that is easy to miss. The 26.2 file adds a
  test-tmp workaround for SQLite's WAL shared memory and the Byte Buddy agent; 1.21.11 adds a JAR
  dedup post-process because Paper's PluginRemapper rejects duplicate entries, and drops
  `--enable-preview`. Copying the wrong one produces a build that fails cryptically or a jar the
  server refuses to load. That is the actual risk in this feature, not the four lines in
  `settings.gradle.kts`.

- **Unverified, and it needs confirming at implementation time:** the Java toolchain 26.3
  requires. The v3 builds endpoint exposes only the server jar download, not the Java version, so
  26.2's Java 25 with `--enable-preview` is an assumption, not a checked fact. Guessing wrong
  here costs a failed toolchain resolution and a wasted build cycle.

- **Config keys:** none. **Permissions:** none. **Database:** no schema change.

- **Tests:** the existing suite, run on 26.3. Plus a boot check, because a passing test run does
  not prove the jar loads on a real server, which is the failure the 1.21.11 dedup workaround
  exists for.

- **Docs:** `changelog.md` gets a line saying 26.3 is supported. `patchnotes.md` gets the
  build and CI changes tagged `*(adds F14)*`. No `Breaking Changes` heading, since nothing an
  operator configures changes.

- **Depends on:** nothing. It is independent of every other item and can land at any point in the
  v1.5.7 cycle.

- **Risk:** low, and reversible. The failure modes are a build that does not compile or a jar the
  server rejects, both of which are caught before release by the suite and the boot check. The
  cost is CI minutes: a fourth matrix entry lengthens every build.

- **Worth considering alongside it, not part of it:** the per-version build files have now been
  copied four times. If a fifth version is added later, a shared convention in `versions/common`
  with only the genuinely differing values declared per version would remove the copy-paste class
  of error entirely. That is a refactor and it does not belong in this feature, so it is recorded
  here as a future decision rather than smuggled in.

---

## 4. Version map

| Version | Theme | State |
|---------|-------|-------|
| v1.5.4.1 | Dashboard market item fix hotfix (H3, H3b, H3c, C3-core) | Published 2026-10-18 |
| v1.5.5 | Money integrity. Fixes first, then F1, F3, F4, F2, F5, F6, F7, F8 | Gated on the fixplan's batches A to E |
| v1.5.6 | Discord alerts (F9, alerts only), Discord on any host (F10), auction listing GUI (F11), CI completeness audit (F15), Aurelium Cloud defects (M15-M19) | Open, F9, F10, F11, F15, M15-M19 assigned |
| v1.5.7 | Discord two-way half (F9b), seller "my listings" (F12), player price watch (F13), Minecraft 26.3 support (F14) | Open, F9b, F12, F13, F14 assigned |

# terra — system test harness

> **Archived.** terra is now maintained internally at Stellantis, where it is adopted
> and developed further. This repository is the public record of the design: it still
> runs, but it takes no issues or pull requests and will not be released again.
> Everything below describes what is here, not how to roll it out.

`terra` owns container lifecycle. Gradle compiles and runs tests. The test JVM
touches nothing — it reads a JSON descriptor and connects to what is already there.

```text
terra run fulfilment
   ├── resolve environments/fulfilment.yml → fingerprint → project terra-93ec8216
   ├── docker compose up -d --wait
   ├── start log + event followers          (live until `down`)
   ├── write build/terra/environments/fulfilment.json  ────┐
   ├── exec ./gradlew systemTest                           │  no Docker in this process
   ├── collect diagnostics                                 │
   └── docker compose down --volumes                       │
                                                           ▼
                       IDE green triangle reads the same descriptor
```

The descriptor is the only contract between the two sides. Once it exists, running
from `terra` and running from the IDE are the same code path.

**Contents** — [Try it](#try-it) · [Layout](#layout) · [Writing a test](#writing-a-test) ·
[Isolation](#isolation-the-one-rule) · [Mocks](#mocks-and-the-simulator) ·
[Commands](#commands) · [Reference](#reference) · [Invariants](#invariants)

---

## Try it

This repository runs as-is on public images. `demo/` is the worked example — it stands
in for the `system-tests/` module of a product repository.

```bash
./terra run                    # two environments in sequence, ~55s warm
./terra list                   # what exists, by environment and group
./terra up fulfilment          # then click the run gutter in your IDE
./terra down fulfilment
```

Docker is the only prerequisite; the Gradle wrapper fetches the rest.

---

## Layout

```text
terra/
  cli/          the `terra` command: fingerprinting, compose, descriptors, collectors
  junit/        the test-side library: TerraContext, the probes, discovery, docs
  compose/      the demo topology — base, stacks, the simulator, chaos
  environments/ fulfilment.yml, returns.yml — two topologies over a shared base
  demo/         the worked example suite, and the generated docs it produces
  terra         thin launcher; rebuilds the CLI then execs it
```

In a product repository, `environments/`, `compose/` and the tests live together in
one `system-tests/` module, and terra is a dependency rather than a sibling. Component
tests stay in the service repositories: Testcontainers is the right tool one level
down, and it does not own the system topology.

### What the demo covers

Seven classes, each the executable statement of one part of the design:

| | |
|---|---|
| `TopologyST` | discovery, declared configuration, internal vs. host addresses, capabilities, migrations |
| `KafkaST` | both Kafka paths — automatic checkpoints and manual ones — and the window/authorship split |
| `MongoST` | the normal seed-act-assert shape, and what `@ExclusiveEnvTest` is actually for |
| `SimulatorST` | per-test rules on a shared mock with no header propagation; a dependency that answers now and reports later |
| `ChaosST` | what the service does while a dependency is unreachable, and that it recovers |
| `ReservationFlowST` | an end-to-end flow, and the worked example for generated documentation |
| `ReturnsST` | a second topology over the same base, and refusal when a capability is absent |

---

## Writing a test

```kotlin
@Tag(Tags.REGRESSION) @Tag(Tags.INVENTORY)   // groups; as many as it belongs to
@Environment("fulfilment")                   // topology, by name, never file paths
class ReserveStockST : SystemTest() {         // suffix ST

    @BeforeEach
    fun seed(ctx: TerraContext) {
        ctx.mongo.inventory.insert(ctx.ids.sku(), "onHand" to 3)
    }

    @SharedEnvTest                           // runs concurrently with other shared tests
    fun `reserves the last unit exactly once`(ctx: TerraContext) {
        ctx.http("orders-api").post("/orders", """{"sku":"${'$'}{ctx.ids.sku()}"}""")

        ctx.kafka.shouldBePublished<StockMoved>("stock-moves") { it.sku == ctx.ids.sku() }
        ctx.mongo.inventory.await(ctx.ids.sku()) { it.getInteger("onHand") == 2 }
    }
}
```

`ctx` arrives by parameter injection. There is no static `Terra.INSTANCE`, because
global mutable state is how a suite discovers three hundred tests later that it can
never be parallelised. Keep the receiver explicit: `ctx.mongo` cannot be shadowed by a
field of the same name, and `ctx.run { mongo… }` can.

### What `ctx` gives you

| | |
|---|---|
| `ctx.ids` | `id(prefix, n)`, plus the kinds you declare; `header`, `group()` |
| `ctx.http("orders-api")` | `get`/`post`, carries `X-Test-Id` at the first hop |
| `ctx.kafka` | `shouldBePublished`, `shouldNotBePublished`, `publish`; `checkpoint`/`awaitAfter`/`readAfter` for undeclared topics |
| `ctx.mongo` | `collection(name)` → `insert`/`get`/`set`/`await`/`mine`/`drop` |
| `ctx.simulator` | `stub`, `send`, `requestsReceived`, `reset` — you name the operations |
| `ctx.endpoint("kafka")` | host and port, from the descriptor |
| `ctx.chaos` | `withNetworkPartition(target) { … }` — exclusive tests only |
| `ctx.requires("simulator")` | refuse early if the topology lacks a capability |
| `eventually { … }` | polling assertion, scaled 20× under a debugger |

### Shared or exclusive

`@SharedEnvTest` only reads shared state and runs 2-at-a-time. `@ExclusiveEnvTest`
drops, truncates, counts globally, restarts or flips shared configuration; JUnit's
`@ResourceLock` guarantees no shared test overlaps it. If a test would be flaky when
something else writes concurrently, it is exclusive.

### What terra cannot discover

Ports come from Docker, endpoints from the descriptor, topics from the environment
file. Four things have to be stated once per JVM, registered by ServiceLoader so
nothing has to remember to call it — see [`TerraSetup`](demo/src/test/kotlin/tests/TerraSetup.kt):

```kotlin
Terra.database = "fulfilment"            // the database your services write to
Terra.simulatorService = "carrier-mock"  // which service is the programmable mock
Terra.allowedLogPatterns += Regex("""Connection refused.*retrying""")
```

`Terra.mongoService` and `Terra.kafkaService` default to `mongodb` and `kafka`.
Everything else a project names, it names as extensions — identifiers
([`Ids.kt`](demo/src/test/kotlin/tests/Ids.kt)), collections and mock operations
([`Simulator.kt`](demo/src/test/kotlin/tests/Simulator.kt)) all follow one pattern, so
there is a single convention to learn rather than three. Adding a kind is a line in
your own project, checked by the compiler rather than spelled as a string at each call
site.

### Environment migrations

Setup belonging to the environment rather than to a test — topics, indexes, static
reference data — registered by ServiceLoader and run once per JVM, in order, before the
first test attaches. See [`SeedReferenceData`](demo/src/test/kotlin/tests/SeedReferenceData.kt).
**They must be idempotent**: the environment outlives the JVM, so an IDE re-run runs
them again and so does every shard. The alternative — a `@BeforeAll` in whichever class
happens to run first — is an ordering dependency wearing a different hat.

### Groups

Groups are `@Tag`, selected with JUnit's own tag expressions (`&`, `|`, `!`, `any()`,
`none()`), so a test belongs to as many as it genuinely belongs to.

```bash
terra run --tag inventory
terra run --tag regression --exclude-tag flaky
```

Two axes, as typed constants so a rename is a compile error: **tier** (`smoke`,
`regression`, `flaky` — a tag, not a quarantine directory) and **domain**
(`inventory`, `shipping`, `returns`). `run` asks for a plan first and skips
environments with no selected tests.

### Generated documentation

A system test is the only executable description of what the system does, so terra
generates Markdown from `@SuiteDoc`/`@TestDoc` annotations — one file per class, kept
beside the tests and committed, so a change to what a test claims shows up in the same
diff as the change to the test. Both annotations are optional; an unannotated class
produces no file.

```bash
./gradlew :demo:testDocs
```

Output: [`demo/docs/`](demo/docs/tests/fulfilment/ReservationFlowST.md), with
hand-written label pages under [`demo/docs/labels/`](demo/docs/labels) whose content
above the `<!-- generated part -->` marker is never touched. The annotations and the
Markdown writer come from
[skodjob/test-metadata-generator](https://github.com/skodjob/test-metadata-generator),
which ships as a Maven plugin; only the Mojo is Maven, so
[`terra.Docs`](junit/src/main/kotlin/terra/Docs.kt) calls `MdGenerator` directly with
the test runtime classpath Gradle already assembled.

---

## Isolation: the one rule

**Scope by an identity nothing else uses. Never clear shared state.**

Clearing forces the suite serial; scoping does not. It applies identically at every
layer, which is why there is only one rule to remember:

| Layer | Scoped by |
|---|---|
| MongoDB | `_id` derived from (execution, test), plus a `testId` stamp on every document |
| Kafka | a checkpoint bounds the window; a test-unique id in the payload bounds authorship |
| Simulator | rules match a test-unique value in the request body |
| Network faults | **not scopeable** — global by nature, so always `@ExclusiveEnvTest` |
| HTTP | `X-Test-Id` header at the first hop, for log searching |

A checkpoint bounds the *window*, not the *authorship* — other tests publish into the
same window — so always also filter by an id only this test knows. Identities come from
`TERRA_EXEC_ID`, which is **per execution, not per environment**: re-running a test
against a retained environment must not reuse ids.

### Configuration is part of the fingerprint

Values are declared once in the environment file; the compose file only names them with
a fallback, so `docker compose config` resolves while the fingerprint is still being
computed. Precedence is **environment variable → environment file → compose default**.

```bash
TERRA_VAR_ORDERS_LOG_LEVEL=DEBUG ./terra up fulfilment
# superseding terra-8808c487 (fingerprint 8808c487 -> ce5a875b)
```

A changed value starts a *new* environment and reaps the old one: you cannot test a new
configuration against a service still running the old one. Assert on it like anything
else — services expose their configuration, and `TopologyST` pins it.

Two rules that save a day each: **never publish fixed host ports** (Docker assigns,
terra discovers — fixed ports stop two environments coexisting), and **every service
needs a healthcheck**, because readiness is `up -d --wait` and terra does not poll.
Services that hand out their own address, as Kafka does, go under `hostPorts:` and get
a stable port derived from the fingerprint.

---

## Mocks and the simulator

Every test reconfigures the shared simulator at runtime. Nothing restarts, nothing is
reset, and rules are scoped on the **payload** rather than a header — the assumption
here is that `X-Test-Id` does *not* survive the hop, so no service changes are
required. A rule matching a value only one test generated can only fire for that test.
Where a call carries no identity at all (`GET /rates`, a token fetch), the honest
options are an `@ExclusiveEnvTest` configured globally, or a dedicated environment with
the behaviour baked into an overlay.

`SimulatorProbe` wraps the WireMock admin API, and no test knows that — which is what
makes it replaceable by a genuine behavioural simulator, for a mock that has to
*behave* rather than answer, without a test changing.

`thenPublish` covers the dependency that answers now and reports later: the caller is
answered immediately and the event lands on Kafka after the stated delay, through a
REST proxy declared beside the simulator. Two details are not optional. Derive the id
**from the request** rather than generating one, because the response and the webhook
are rendered independently. And the webhook fires **from inside the container network**,
so its address is a service name and a container port (`Terra.kafkaRestUrl`), never an
endpoint from the descriptor — those are addresses for the test, not for a service
calling a service.

---

## Commands

```text
terra up      <environment>     start it and write the descriptor
terra down    <environment>     stop it, remove volumes, delete the descriptor
terra status  <environment>     docker compose ps
terra logs    <environment>     follow the cluster log
terra run     [environment...]  up → gradle systemTest → diagnostics → down
terra list                      what would run, by environment and group
terra prune                     reap every terra project no descriptor points at
```

`up` is idempotent — `up -d --wait` reconciles a healthy project in about a second.
Rebuild a service and the fingerprint changes, so `up` starts a *new* project and reaps
the superseded one. `down` deletes the descriptor first, so nothing can attach
mid-teardown, then kills the log followers by pid.

With no environment running the test JVM gives you the command that fixes it, not a
forty-second stall:

```text
Cannot run: no environment is running for environment 'fulfilment'.

    ./terra up fulfilment

The test JVM never starts containers — that is the harness's job.
```

---

## Reference

### Options

| Flag | Default | Meaning |
|---|---|---|
| `--tag <expr>` | — | run only these groups; repeatable, OR-ed |
| `--exclude-tag <expr>` | — | skip these groups; repeatable, OR-ed |
| `--project-dir <path>` | `.` | where `./gradlew` runs |
| `--task <name>` | `systemTest` | Gradle task to invoke |
| `--plan-task <name>` | `systemTestPlan` | Gradle discovery task |
| `--keep` | off | retain the environment if tests fail |

### Environment variables

| Name | Default | Meaning |
|---|---|---|
| `TERRA_RUN_ID` | random | groups results under `build/results/<id>` |
| `TERRA_EXEC_ID` | random | identity prefix; **per execution**, never per environment |
| `TERRA_KEEP` | `false` | same as `--keep` |
| `TERRA_SHARD` | unset | `2/8`; deterministic `hash(uniqueId) % n` |
| `TERRA_TAGS` / `TERRA_EXCLUDE_TAGS` | unset | tag expressions; set by `--tag` |
| `TERRA_VAR_<NAME>` | — | overrides `vars.<NAME>` |
| `TERRA_TIMEOUT_SCALE` | `1` | multiplies every wait; auto-20 under a debugger |
| `TERRA_ENV` / `TERRA_ENV_FILE` | unset | set by `run`; pin the test JVM to one environment |
| `TERRA_DESCRIPTOR_DIR` | derived | otherwise found by walking up to `environments/` |
| `TERRA_NO_BUILD` | unset | `1` skips the Gradle build in the launcher |

### Descriptor

`build/terra/environments/<name>.json` — the only thing the test JVM knows about
infrastructure. Name, fingerprint, project, runId, results dir, cluster log path,
health URL, endpoints, capabilities, collector pids.

### Results

```text
build/results/<runId>/
    cluster/<env>/
        cluster.log        every container's stdout, interleaved and timestamped
        events.jsonl       container lifecycle events
    tests/<package>/<Class>/<id>-<name>/      failing tests only
        timeline.txt
        logs/<container>.log
```

A failing test writes a **timeline** of what it did and prints it beside the failure:

```text
ReserveStockST > reserves the last unit exactly once
  ▶   536ms  simulator rule p5 -> 201                             201
  ▶   752ms  mongo     insert orders ORD-b577-s9dj-001            Document{{_id=…, state=NEW}}
  ▶   805ms  http      POST /carrier                              201
  ▶   840ms  kafka     publish -> stock-moves                     stock-moves-0@0
  ▶  1152ms  chaos     partition store-simulator                  severed
  ▶  1160ms  http      POST /carrier                              503
  ▶  1165ms  chaos     heal store-simulator                       restored
  ✘  1166ms  kafka     shouldBePublished<ShipmentReady> shipments no matching … within PT2S
```

The assertion tells you what was untrue; the timeline tells you what happened before
it, which is usually the diagnosis. Recorded by the probes themselves, so tests write
nothing to get it. Logs and container events are collected from the moment the
environment is up — not after a failure, by which time the interesting seconds have
scrolled past. Every passing test also asserts that no service logged an unexpected
error, against one short reviewable allowlist.

### Build wiring

The test side needs three Gradle tasks, all of which read compiled classes and start
nothing: `systemTest` (with `outputs.upToDateWhen { false }`, because a system test's
real input is a live environment that Gradle cannot hash), `systemTestPlan`
(`terra.Discover` — discovery without execution, so `run --tag x` knows which
environments it needs) and `testDocs` (`terra.Docs`). All three pass the `TERRA_*`
variables through. [`demo/build.gradle.kts`](demo/build.gradle.kts) is the reference
copy.

---

## Invariants

1. **Only `terra` touches Docker.** Not Gradle, not the test JVM.
2. **Tear down only what you started.** Nothing implicit destroys an environment.
3. **Refuse, don't degrade.** A missing environment or capability is an error with a
   fix, never a silent fallback.
4. **Logs are collected from the moment the environment is up**, not after a failure.
5. **The descriptor is the only thing the test JVM knows about infrastructure.**
6. **Scope by identity; never clear shared state.**

## Licence

MIT — see [LICENSE](LICENSE).

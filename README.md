# terra — system test harness

`terra` owns container lifecycle. Gradle compiles and runs tests. The test JVM
touches nothing — it reads a JSON descriptor and connects to what is already there.

```text
terra run fulfilment
   ├── resolve environments/fulfilment.yml → fingerprint → project terra-93ec8216
   ├── docker compose up -d --wait
   ├── start log + event followers          (live until `down`)
   ├── write build/terra/environments/fulfilment.json  ──┐
   ├── exec ./gradlew systemTest                           │  no Docker in this process
   ├── collect diagnostics                                 │
   └── docker compose down --volumes                       │
                                                           ▼
                       IDE green triangle reads the same descriptor
```

The descriptor is the only contract between the two sides. Once it exists, running
from `terra` and running from the IDE are the same code path.

**Contents** — [Try it](#try-it) · [Adopting this](#adopting-this-in-your-product) ·
[Writing a test](#writing-a-test) · [Isolation](#isolation-the-one-rule) ·
[Services & config](#configuring-a-service) · [Mocks](#mocks-and-the-simulator) ·
[Groups](#groups) · [Commands](#commands) · [Reference](#reference) ·
[Caching](#caching) · [Traps](#traps-already-paid-for) · [Not built](#not-built-yet)

---

## Try it

This repository runs as-is on public images. `demo/` stands in for your
`system-tests/` module.

```bash
./terra run                    # two environments in sequence, ~55s warm
./terra list                   # what exists, by environment and group
./terra up fulfilment          # then click the run gutter in your IDE
./terra down fulfilment
```

---

## Adopting this in your product

### 1. What goes where

terra is generic. Environments, compose files and tests are yours and belong
together, in one module.

```text
your-monorepo/
  services/
    order-service/            unit + component tests (Testcontainers) stay here
    inventory-service/
  system-tests/               ← everything system-level
    build.gradle.kts
    environments/             fulfilment.yml  returns.yml  …
    compose/
      base.yml                shared infrastructure
      stacks/                 one per domain slice
      features/               optional components
      faults/                 fault injection overlays
    src/test/kotlin/tests/
      inventory/ReserveStockST.kt
      shipping/…
    src/test/resources/junit-platform.properties
  # terra itself is a separate repository — checked out beside this one,
  # or a published dependency. It is not vendored into your tree.
```

Component tests stay in the service repositories. Testcontainers is the right tool
one level down; it does not own the system topology.

### 2. Wire the build

`settings.gradle.kts`:

```kotlin
includeBuild("../terra")     // a sibling checkout while it moves; a coordinate later
```

`system-tests/build.gradle.kts`:

```kotlin
import org.gradle.process.CommandLineArgumentProvider

plugins { kotlin("jvm") }

dependencies {
    testImplementation("terra:junit")
    testImplementation(project(":services:order-service:api"))     // clients, event schemas
    testImplementation("org.assertj:assertj-core:3.26.3")
}

// Nothing here but system tests, so the default `test` task would only ever fail:
// there is no environment during a plain `./gradlew build`.
tasks.test { enabled = false }

val PASSTHROUGH = listOf(
    "TERRA_ENV", "TERRA_ENV_FILE", "TERRA_SHARD", "TERRA_RUN_ID",
    "TERRA_TAGS", "TERRA_EXCLUDE_TAGS",
)

val systemTest = tasks.register<Test>("systemTest") {
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    useJUnitPlatform()
    testLogging { events("passed", "failed", "skipped") }

    // A system test's real input is a live environment, which Gradle cannot hash.
    // Without this a task that passed once is UP-TO-DATE forever — including against
    // a *different* environment, which is silently no test at all.
    outputs.upToDateWhen { false }

    PASSTHROUGH.forEach { n -> providers.environmentVariable(n).orNull?.let { environment(n, it) } }
}

// Discovery without execution, so `run --tag x` knows which environments it needs.
val systemTestPlan = tasks.register<JavaExec>("systemTestPlan") {
    dependsOn(tasks.named("testClasses"))
    classpath = sourceSets["test"].runtimeClasspath
    mainClass = "terra.Discover"
    val classesDirs = sourceSets["test"].output.classesDirs
    argumentProviders.add(CommandLineArgumentProvider { listOf(classesDirs.asPath) })
    PASSTHROUGH.forEach { n -> providers.environmentVariable(n).orNull?.let { environment(n, it) } }
}
```

`system-tests/src/test/resources/junit-platform.properties`:

```properties
junit.jupiter.execution.parallel.enabled=true
junit.jupiter.execution.parallel.mode.default=same_thread
junit.jupiter.execution.parallel.config.strategy=fixed
junit.jupiter.execution.parallel.config.fixed.parallelism=2
```

`same_thread` by default; `@SharedEnvTest` opts each test into concurrency. Safe
direction to be wrong in.

### 3. Describe your first environment

Start with **one** environment containing your smallest useful slice. Resist adding a
second until the first is green in CI.

```yaml
# system-tests/environments/fulfilment.yml
compose:
  - compose/base.yml
  - compose/stacks/fulfilment.yml

services:                 # service -> container port; terra discovers the host port
  gateway: 8080
  orders-api: 8080
  mongodb: 27017
  kafka: 9092

hostPorts:                # only services that hand out their own address
  kafka: 9092

topics: [shipments, stock-moves]     # created if absent; checkpointed every test

health: gateway:8080/actuator/health

capabilities: [kafka, mongo, orders]

vars:
  ORDERS_VERSION: 1.17.4
  ORDERS_LOG_LEVEL: INFO
  # …the rest of the service configuration
```

Rules that save a day each:

- **Never publish fixed host ports** in compose. Docker assigns, terra
  discovers. Fixed ports stop two environments coexisting, which is what makes
  attach-and-run possible.
- **Every service needs a healthcheck.** Readiness is `up -d --wait`; terra does
  not poll. No healthcheck means "ready" means "the process started".
- **The topology must stay startable by hand.** Whatever `terra` runs, this must
  work too:
  ```bash
  KAFKA_HOST_PORT=29092 docker compose -f compose/base.yml -f compose/stacks/fulfilment.yml up
  ```

### 4. Write your first test

```kotlin
package tests.inventory

import terra.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.assertj.core.api.Assertions.assertThat
import tests.Tags

@Tag(Tags.SMOKE) @Tag(Tags.INVENTORY)
@Environment("fulfilment")
class ReserveStockST : SystemTest() {

    @BeforeEach
    fun seed(ctx: TerraContext) {
        ctx.mongo.inventory.insert(ctx.ids.sku(), "onHand" to 3)
    }

    @SharedEnvTest
    fun `reserves the last unit exactly once`(ctx: TerraContext) {
        ctx.http("orders-api").post("/orders", """{"sku":"${'$'}{ctx.ids.sku()}"}""")

        ctx.kafka.shouldBePublished<StockMoved>("stock-moves") { it.sku == ctx.ids.sku() }
        ctx.mongo.inventory.await(ctx.ids.sku()) { it.getInteger("onHand") == 2 }
    }
}
```

### 5. Run it

```bash
terra up fulfilment --project-dir system-tests    # once, pay the startup
# …then the IDE run gutter works indefinitely, instantly
terra run --project-dir system-tests --tag inventory
terra down fulfilment --project-dir system-tests
```

The test JVM never starts containers. With no environment running you get the command
that fixes it, not a forty-second stall:

```text
Cannot run: no environment is running for environment 'fulfilment'.

    ./terra up fulfilment

The test JVM never starts containers — that is terra's job.
```

### 6. Run it in CI

```yaml
strategy:
  matrix: { shard: [1, 2, 3, 4] }
steps:
  - run: terra run --project-dir system-tests
    env:
      TERRA_SHARD: ${{ matrix.shard }}/4
  - uses: actions/upload-artifact@v4
    if: always()
    with: { name: results-${{ matrix.shard }}, path: system-tests/build/results/ }
```

Anything more in that file is orchestration a developer cannot run.

### 7. Suggested rollout order

| | Do | Done when |
|---|---|---|
| 1 | One environment, three smoke tests, run locally | `terra run` is green on a laptop |
| 2 | The same in CI, artifacts uploaded | a deliberate failure is diagnosable from the artifact alone |
| 3 | Port ~20 existing scenarios; add tags | `terra list` reads like a coverage map |
| 4 | Second environment; check the fingerprint grouping pays | two topologies run in sequence |
| 5 | Sharding across CI runners | wall-clock scales with shard count |
| 6 | Mocks consolidated behind `SimulatorProbe` | no test knows what the mock server is |
| 7 | Fault environments (`compose/faults/`), exclusive tests | the nasty cases have a home |

Do not build the results dashboard, the flake tracker or the custom reporter until
someone has asked twice.

---

## Writing a test

### The shape

```kotlin
@Tag(Tags.REGRESSION) @Tag(Tags.INVENTORY)   // groups; as many as it belongs to
@Environment("fulfilment")                   // topology, by name, never file paths
class ReserveStockST : SystemTest() {        // suffix ST

    @BeforeEach
    fun seed(ctx: TerraContext) { … }      // fixtures, per test

    @SharedEnvTest                           // runs concurrently with other shared tests
    fun `reserves the last unit exactly once`(ctx: TerraContext) { … }
}
```

`ctx` arrives by parameter injection. There is no static `Terra.INSTANCE`, because
global mutable state is how a suite discovers three hundred tests later that it can
never be parallelised.

### What `ctx` gives you

| | |
|---|---|
| `ctx.ids` | `id(prefix, n)`, plus the kinds you declare; `header`, `group()` |
| `ctx.http("orders-api")` | `get`/`post`, carries `X-Test-Id` at the first hop |
| `ctx.kafka` | `shouldBePublished`, `shouldNotBePublished`, `publish`; `checkpoint`/`awaitAfter`/`readAfter` for undeclared topics |
| `ctx.mongo` | `orders`, `inventory`, `collection(name)` → `insert`/`get`/`set`/`await`/`mine`/`drop` |
| `ctx.simulator` | `acceptOrders`, `rejectOrdersFrom`, `failOrders`, `placeOrder`, `ordersReceived` |
| `ctx.endpoint("kafka")` | host and port, from the descriptor |
| `ctx.chaos` | `withNetworkPartition(target) { … }` — exclusive tests only |
| `ctx.requires("simulator")` | refuse early if the topology lacks a capability |
| `eventually { … }` | polling assertion, scaled 20× under a debugger |

### Your own identifiers

Terra generates the part that must be unique — the execution and the test — and knows
nothing about what your domain calls things. You name the kinds once, next to your
tags:

```kotlin
// system-tests/src/test/kotlin/tests/Ids.kt
fun TestIds.order(n: Int = 1) = id("ORD", n)
fun TestIds.sku(n: Int = 1) = id("SKU", n)
fun TestIds.customer(n: Int = 1) = id("CUS", n)
```

```kotlin
ctx.mongo.orders.insert(ctx.ids.order(), "sku" to ctx.ids.sku())
ctx.simulator.rejectOrdersFrom(ctx.ids.customer())
```

Adding a kind is a line in your own project, not a change to the harness — and the
kind is checked by the compiler rather than spelled as a string at each call site.
`n` distinguishes several of the same kind inside one test and is explicit rather
than a counter, so a rerun produces the same ids, which matters when you are reading
them back out of a log.

Keep prefixes short and recognisable: `ORD-8f30-a1b2-001` should tell whoever is
grepping at 3am both what it is and which test made it.

If you would rather not import the extensions in every file, declare them as member
extensions on a project base class (`abstract class FulfilmentTest : SystemTest()`)
and they are in scope for every subclass.

### Keep `ctx.` explicit

Kotlin will happily let you drop the receiver:

```kotlin
fun `a test`(ctx: TerraContext) { ctx.run {          // don't
    mongo.orders.insert(ids.order(), …)
    http("orders-api").post("/carrier", …)
} }
```

It reads tidily and it is a bad trade. `mongo`, `ids` and `http` now come from nowhere
visible, and a reader has to know terra to know they are not locals, fields, or
imports. That is the cost every day.

The occasional cost is worse: an implicit receiver **can be shadowed**. Give the test
class a field called `mongo`, or nest the block inside another scope function, and
resolution silently changes to something else that also compiles. `ctx.mongo` cannot
do that.

Where the repetition genuinely grates, bind a named local — which says *more* than the
receiver trick did, because it names the domain concept:

```kotlin
val blocked = ctx.ids.user()
val order = ctx.ids.order()

ctx.simulator.rejectOrdersFrom(blocked)
ctx.mongo.orders.await(order) { it.getString("state") == "RESERVED" }
```

### Shared or exclusive

```kotlin
@SharedEnvTest      // default: only reads shared state; runs 2-at-a-time
@ExclusiveEnvTest   // drops, truncates, counts globally, restarts, flips shared config
```

If a test would be flaky when something else writes concurrently, it is exclusive.
JUnit's `@ResourceLock` guarantees no shared test overlaps it — measured, not assumed.

### Kafka assertions

Declare the topics in the environment file and terra marks all of them at the
start of every test, before it can act — so no test takes a checkpoint by hand:

```yaml
topics: [shipments, stock-moves]
```
```kotlin
ctx.kafka.shouldBePublished<StockMoved>("stock-moves") { it.sku == ctx.ids.sku() }
ctx.kafka.shouldNotBePublished<ShipmentReady>("shipments") { it.order == ctx.ids.order() }
```

The *window* is handled for you; the *authorship* is still yours, so match on an id
only this test generated. `shouldNotBePublished` defaults to a two-second timeout,
because an absence proven with the thirty-second default costs thirty seconds every
run. `checkpoint`/`awaitAfter` remain for topics outside the declared set.

### Environment migrations

Setup that belongs to the environment rather than to a test — topics, indexes, static
reference data — registered by ServiceLoader and run once per JVM, in order, before
the first test attaches:

```kotlin
class SeedReferenceData : EnvironmentMigration {
    override val name = "seed carriers and warehouses"
    override val environment = "fulfilment"
    override val order = 10

    override fun apply(ctx: MigrationContext) {
        ctx.topic("returns-requested")                       // create if absent
        ctx.mongo.getCollection("carriers").replaceOne(…, upsert())
    }
}
```

**They must be idempotent**: the environment outlives the JVM, so an IDE re-run runs
them again and so does every shard. Topics listed under `topics:` are created for you
before any migration runs. The alternative — a `@BeforeAll` in whichever class happens
to run first — is an ordering dependency wearing a different hat.

### Network faults

A Toxiproxy sits between a caller and a dependency. Disabling it severs the connection
while leaving the dependency healthy and directly observable *by the test*, which is
what makes assertions during an outage possible:

```kotlin
@ExclusiveEnvTest
fun `a partition makes the dependency unreachable, and it recovers`(ctx: TerraContext) {
    ctx.chaos.withNetworkPartition("store-simulator") {
        // unreachable to the caller in here…
        assertThat(ctx.http("store-simulator").get("/__admin/mappings").status).isEqualTo(200)
        // …but the test can still see it
    }
    // healed — on success, on failure, and on cancellation
}
```

This replaces a `compose/faults/` overlay for anything short-lived: no second topology,
no restart, and recovery is asserted in the same test as the failure.

**Always exclusive.** A severed connection is severed for everybody, so this is the one
probe that cannot be scoped by identity. It refuses outside `@ExclusiveEnvTest` rather
than quietly cutting off whatever else is in flight.

The proxy has to be in the path from the start — you cannot reroute a running service —
so the caller is pointed at the proxied address as a topology decision, and it is
transparent while enabled.

### Things not to do

| Don't | Do |
|---|---|
| start a container | name a topology with `@Environment` |
| `Thread.sleep(5000)` | `eventually { … }` |
| truncate a collection or topic | derive ids; take a checkpoint |
| hard-code a port | `ctx.endpoint(service)` |
| use a shared fixture name (`"frank"`) | `ctx.ids.user()` |
| hide the context with `ctx.run { }` / `with(ctx)` | keep `ctx.` explicit; see below |
| share fixtures between tests | seed in `@BeforeEach` |
| put a plain `@Test` on it | `@SharedEnvTest` / `@ExclusiveEnvTest` |

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

```kotlin
val mark = ctx.kafka.checkpoint("shipments")   // read forward from here
ctx.mongo.orders.insert(ctx.ids.order(), …)    // an id nothing else can collide with
```

A checkpoint bounds the *window*, not the *authorship* — other tests publish into the
same window — so always also filter by an id only this test knows.

Identities come from `TERRA_EXEC_ID`, which is **per execution, not per
environment**: re-running a test against a retained environment must not reuse ids.

---

## Configuring a service

A service with a hundred environment variables has its largest failure surface exactly
there, so terra makes configuration assertable.

Values are declared once, in the environment file; the compose file only names them
with a fallback so `docker compose config` resolves while the fingerprint is still
being computed:

```yaml
# environments/fulfilment.yml
vars:
  ORDERS_LOG_LEVEL: INFO
  ORDERS_FEATURE_EXPRESS_LANE: "false"
```
```yaml
# compose/stacks/fulfilment.yml
environment:
  ORDERS_LOG_LEVEL: ${ORDERS_LOG_LEVEL:-INFO}
```

**Precedence: environment variable → environment file → compose default.**

```bash
TERRA_VAR_ORDERS_LOG_LEVEL=DEBUG ./terra up fulfilment
```
```text
superseding terra-8808c487 (fingerprint 8808c487 -> ce5a875b)
```

Configuration is part of the fingerprint, so a changed value starts a *new* environment
and reaps the old one. You cannot test a new configuration against a service still
running the old one.

Assert on it like anything else — the service exposes its configuration
(`/actuator/configprops` in Spring):

```kotlin
val config = ctx.http("orders-api").get("/config").json()
assertThat(config["featureExpressLane"].asBoolean()).isFalse()
```

### Static inside, dynamic outside

One database, two addresses, and only one of them is dynamic:

| From | Address | Dynamic? |
|---|---|---|
| another **service**, inside the Docker network | `mongodb:27017` | no — service name, container port |
| a **test**, from the host | `localhost:57457` | yes — Docker chose it at `up` |

Configure services with the internal name; discover the external one from the
descriptor. `AddressingST` pins both halves.

Three cases where an address really is dynamic: the service publishes its own address
(→ `hostPorts:`, as Kafka does); the dependency lives outside the compose project
(→ `vars:` or an `external: true` network); the value is only knowable after start
(→ no env-var answer exists; mount a file).

---

## Mocks and the simulator

Every test can reconfigure the shared simulator at runtime. Nothing restarts and
nothing is reset.

```kotlin
@BeforeEach
fun defaultBehaviour(ctx: TerraContext) {
    ctx.requires("simulator")
    ctx.simulator.acceptOrders()
}

@SharedEnvTest
fun `a blocked user is rejected while everyone else is served`(ctx: TerraContext) {
    val blocked = ctx.ids.user()               // usr-8f30-a1b2-001; nobody else has it

    ctx.simulator.rejectOrdersFrom(blocked, status = 409, reason = "USER_BLOCKED")

    assertThat(ctx.simulator.placeOrder(user = blocked).status).isEqualTo(409)
    assertThat(ctx.simulator.placeOrder(user = ctx.ids.user(2)).status).isEqualTo(201)
}
```

### Scoping without header propagation

Assume `X-Test-Id` does **not** survive the hop: the test drives service A, A calls the
mocked service B, and A forwards nothing. That is the default assumption here, and no
service changes are required.

Rules therefore scope on the **payload**, using an identity only this test generated.
The rule is self-scoping — it can only fire for a request carrying a value nobody else
has. `SimulatorST` runs two such tests concurrently against the same container to
prove it.

The trap this replaces is the shared fixture name. Whenever you put a literal in a
matcher, ask what stops another test using the same literal.

If your services ever do propagate the header,
`ctx.simulator.scopedByTestIdHeader()` adds header matching on top. Opt-in, because a
rule matching a header that never arrives is a rule that silently never fires.

### When nothing test-unique reaches the mock

Some calls carry no identity — `GET /rates`, a token fetch. Nothing can scope them.
Two honest options: make it an **`@ExclusiveEnvTest`** and configure globally, or give
it a **dedicated environment** with the behaviour baked into an overlay.

### It is WireMock, and tests never find out

`SimulatorProbe` wraps the admin API. Keep a genuine behavioural simulator — state
machines, a controllable clock — as a separate service for what rules cannot express.
When you build one, only `SimulatorProbe` changes; no test does.

---

## Groups

Groups are `@Tag`, so a test belongs to as many as it genuinely belongs to.

```bash
terra run --tag inventory
terra run --tag "inventory | shipping"
terra run --tag regression --exclude-tag flaky
terra list --tag returns
```

Selection uses JUnit's own tag expressions — `&`, `|`, `!`, parentheses, `any()`,
`none()`. Repeating `--tag` ORs them.

Two axes, as typed constants so a rename is a compile error:

- **tier** — `smoke`, `regression`, `flaky`. `flaky` is a tag, not a quarantine
  directory: visible, runnable, excludable.
- **domain** — `inventory`, `shipping`, `returns`.

`run` asks for a plan first and **skips environments with no selected tests**:

```text
$ terra run --tag returns
skipping fulfilment — no selected tests
```

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
results/<runId>/
    cluster/  cluster.log  events.jsonl
    tests/<package>/<Class>/<id>-<name>/
        timeline.txt
        logs/<service>.log
```

A failing test writes a **timeline** of what it did, and prints it beside the failure:

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

The assertion tells you what was untrue. The timeline tells you what happened before
it, which is usually the diagnosis. Recorded by the probes themselves, so tests write
nothing to get it.

Logs and container events are collected from the moment the environment is up — not
after a failure, by which time the interesting seconds have scrolled past. Every
passing test also asserts that no service logged an unexpected error; the allowlist is
one short reviewable file.

---

## Caching

| Layer | State | Buys |
|---|---|---|
| **The environment itself** | attach-or-start | **~40 s → ~1 s** |
| Isolated Projects + configuration cache | on | ~12 s → ~2 s per invocation |
| Build cache (local) | on | `compileKotlin FROM-CACHE` |
| Kotlin incremental, daemon, dependency cache | on | — |
| **`systemTest` task output** | **must stay off** | — |

Gradle 9.7 with Isolated Projects: no project may configure another, so the root build
file is empty and shared decisions live in `settings.gradle.kts`.

`terra` passes `TERRA_ENV`/`TERRA_TAGS` as environment variables read at
configuration time, which makes each value a separate cache key. Keep one entry per
combination you use:

```properties
org.gradle.configuration-cache.entries-per-key=8
```

---

## Traps already paid for

Every one of these cost real time here. They are fixed in terra; they will bite
again in your own compose files.

| Trap | Symptom | Fix |
|---|---|---|
| Compose resolves relative bind mounts against the **first `-f` file's** directory | volume silently becomes an empty directory | `--project-directory` (terra does this) |
| Overriding `command:` skips nginx's `/docker-entrypoint.d/` | `localhost` → `::1` → connection refused in healthchecks | healthcheck on `127.0.0.1`, never `localhost` |
| Kafka advertises its own address | clients redirected to an unreachable port | `hostPorts:` — a port derived from the fingerprint |
| Gradle caches the test task | second environment reports `BUILD SUCCESSFUL in 1s`, runs nothing | `outputs.upToDateWhen { false }` |
| Identities derived from the environment's runId | duplicate-key errors on the second run against a retained environment | per-execution `TERRA_EXEC_ID` |
| Batch-reading Kafka after a checkpoint | another test's events interleaved; looks like an ordering bug | the read predicate is mandatory |
| Awaitility reports its own timeout | `ConditionTimeoutException: … null` | `eventually` rethrows the last real failure |
| Excluding a class descriptor in a JUnit filter | its methods still run | filter `MethodSource` too |
| Cleanup queries that are not scoped | one test wipes every concurrent test's state | scope cleanup, or do not clean |
| Matching log lines on `\w+Exception` | services name exception classes at INFO and WARN; unrelated tests fail | match on the error *level*, or on a line that is a stack trace |
| Expression-bodied test (`fun x() = ctx.run { … }`) | JUnit reports `No tests found` — a non-Unit return is silently not discovered | use a block body |

---

## Not built yet

Additive; none of it changes anything above:

- **Golden event histories** — commit the scrubbed events a scenario emits and diff
  them. The only mechanism that tests compatibility *between versions of your own
  services*. Add it the first time a consumer team is broken by an event change.
- **`@Requires` as an annotation** rather than a runtime call, so a topology that
  cannot satisfy a test is skipped before anything starts.
- **System snapshots in failure artifacts** — the failing test's Kafka window, its
  documents and its simulator rules, beside the timeline.
- **Distributed tracing** — an OTel collector in the topology and the Java agent on the
  services would turn the timeline into a call tree through the application. Worth it
  only once the services are instrumented for production anyway.
- **Generated test documentation** from annotations, once nobody can name what the
  suite covers.
- **Duration-weighted sharding**; `hash % n` until you have history worth using.
- **Long-lived shared infrastructure** on an external network, if per-environment
  Kafka/Mongo restarts start to hurt.
- **`X-Test-Id` propagation** through the services. Not required by anything above;
  it would enable header-scoped mock rules and make a 50 MB cluster log greppable by
  test. If the services already run distributed tracing this is configuration rather
  than code.

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

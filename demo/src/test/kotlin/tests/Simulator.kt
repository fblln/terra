package tests

import com.fasterxml.jackson.databind.JsonNode
import terra.HttpProbe
import terra.MongoProbe
import terra.SimulatorProbe
import java.time.Duration

/**
 * What this product's mocked dependency can be asked to do — named once, the way
 * [Tags] names the groups and `Ids.kt` names the identifiers.
 *
 * Terra provides stubbing, sending and inspection and knows nothing about `/orders`
 * or what a rejection looks like. Adding an operation is a line here, not a change to
 * the harness.
 *
 * Every rule is scoped by a value only the calling test generated — pass
 * `ctx.ids.user()`, never a shared literal.
 */

private const val ORDERS = "/orders"

/** Catch-all success, at a priority anything specific can override. */
fun SimulatorProbe.acceptOrders(): SimulatorProbe =
    stub(path = ORDERS, priority = 5, status = 201, body = """{"status":"ACCEPTED"}""")

/** Reject one user, leaving every other user — and every other test — untouched. */
fun SimulatorProbe.rejectOrdersFrom(
    user: String,
    status: Int = 409,
    reason: String = "USER_BLOCKED",
): SimulatorProbe = stub(
    path = ORDERS,
    priority = 1,
    status = status,
    body = """{"status":"REJECTED","reason":"$reason","user":"$user"}""",
    matchingBody = "$[?(@.user == '$user')]",
)

/** Make the dependency unavailable, to see what the system does about it. */
fun SimulatorProbe.failOrders(status: Int = 503): SimulatorProbe =
    stub(path = ORDERS, priority = 1, status = status, body = """{"status":"UNAVAILABLE"}""")

/** Stand in for the service that would normally place the call. */
fun SimulatorProbe.placeOrder(user: String, sku: String = "SKU-1"): HttpProbe.Response =
    send(path = ORDERS, body = """{"user":"$user","sku":"$sku"}""")

/**
 * A carrier that accepts now and ships later — the asynchronous dependency a canned
 * response cannot express.
 *
 * The shipment id is derived from the order rather than random, so the response and
 * the event four seconds later carry the same one: the two templates are rendered
 * independently, and `{{randomValue}}` in both would produce two different values.
 * Deriving it also means the test can predict it without reading it back.
 *
 * Scoped, like every other rule here, by an id only the calling test generated.
 */
fun SimulatorProbe.shipOrder(
    order: String,
    after: Duration = Duration.ofSeconds(4),
): SimulatorProbe = stub(
    path = ORDERS,
    priority = 1,
    status = 201,
    body = """{"status":"ACCEPTED","shipment":"SHP-{{jsonPath request.body '$.order'}}"}""",
    matchingBody = "$[?(@.order == '$order')]",
    thenPublish = SimulatorProbe.Publish(
        topic = "shipments",
        value = """{"order":"{{jsonPath originalRequest.body '$.order'}}","state":"SHIPPED"}""",
        after = after,
    ),
)

/** Stand in for the service placing that order. */
fun SimulatorProbe.submitOrder(order: String): HttpProbe.Response =
    send(path = ORDERS, body = """{"order":"$order"}""")

/**
 * What this test sent, scoped by the user only it generated.
 *
 * The journal is shared and unpruned, so this predicate runs over every request any
 * test has made to `/orders` — including ones with a different shape entirely, like
 * [submitOrder]'s. It has to tolerate a missing field rather than assume its own.
 */
fun SimulatorProbe.ordersReceived(matchingUser: String): List<JsonNode> =
    requestsReceived(path = ORDERS) { it["user"]?.asText() == matchingUser }

// --- collections, for the same reason ---------------------------------------

val MongoProbe.orders get() = collection("orders")

val MongoProbe.inventory get() = collection("inventory")

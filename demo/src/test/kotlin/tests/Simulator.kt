package tests

import com.fasterxml.jackson.databind.JsonNode
import terra.HttpProbe
import terra.MongoProbe
import terra.SimulatorProbe

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

/** What this test sent, scoped by the user only it generated. */
fun SimulatorProbe.ordersReceived(matchingUser: String): List<JsonNode> =
    requestsReceived(path = ORDERS) { it["user"].asText() == matchingUser }

// --- collections, for the same reason ---------------------------------------

val MongoProbe.orders get() = collection("orders")

val MongoProbe.inventory get() = collection("inventory")

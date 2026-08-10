package tests

import terra.TestIds

/**
 * The identities this product deals in — declared once, the way [Tags] declares the
 * groups.
 *
 * Terra generates the part that has to be unique (the execution and the test) and
 * knows nothing about what your domain calls things. You name the kinds, as extension
 * functions, and every test then reads `ctx.ids.order()` with the kind checked by the
 * compiler instead of spelled out as a string at each call site.
 *
 * Adding one is a line here, not a change to the harness:
 *
 * ```kotlin
 * fun TestIds.shipment(n: Int = 1) = id("SHP", n)
 * ```
 *
 * Keep the prefixes short and recognisable in a log. `ORD-8f30-a1b2-001` should tell
 * whoever is grepping at 3am both what it is and which test made it.
 */

fun TestIds.order(n: Int = 1) = id("ORD", n)

fun TestIds.sku(n: Int = 1) = id("SKU", n)

/**
 * The one that scopes a downstream mock. If the value a service passes onward is
 * unique to this test, a simulator rule can match on it and no header has to survive
 * the hop.
 */
fun TestIds.user(n: Int = 1) = id("usr", n)

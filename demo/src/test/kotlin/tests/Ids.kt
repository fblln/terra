package tests

import terra.TestIds

/**
 * The identities this product deals in — declared once, the way [Tags] declares the
 * groups. Terra generates the part that has to be unique; the kinds are yours, and
 * adding one is a line here rather than a change to the harness.
 *
 * Keep the prefixes recognisable in a log: `ORD-8f30-a1b2-001` should tell whoever is
 * grepping at 3am both what it is and which test made it.
 */

fun TestIds.order(n: Int = 1) = id("ORD", n)

fun TestIds.sku(n: Int = 1) = id("SKU", n)

/** The one that scopes a downstream mock: unique per test, so a rule can match on it. */
fun TestIds.user(n: Int = 1) = id("usr", n)

package tests

/**
 * Groups, as constants rather than free strings — a rename is then a compile error
 * instead of a test that silently stops being in its group.
 *
 * Two axes, and a test usually carries one from each:
 *   tier    how much you run and when
 *   domain  what part of the product it covers
 *
 * A test can be in as many groups as it likes; that is the point of tags over
 * directories. Note that `flaky` is a tag and not a quarantine folder — visible,
 * runnable, and excludable, rather than quietly rotting somewhere else.
 */
object Tags {
    // tier
    const val SMOKE = "smoke"
    const val REGRESSION = "regression"
    const val FLAKY = "flaky"

    // domain
    const val INVENTORY = "inventory"
    const val SHIPPING = "shipping"
    const val RETURNS = "returns"
}

package tests

import org.junit.platform.launcher.LauncherSession
import org.junit.platform.launcher.LauncherSessionListener
import terra.Terra

/**
 * The handful of things terra cannot discover, stated once per JVM.
 *
 * Registered by ServiceLoader, so it runs before any test and lives outside the test
 * sources — nothing has to remember to call it.
 */
class TerraSetup : LauncherSessionListener {
    override fun launcherSessionOpened(session: LauncherSession) {
        Terra.database = "systest"
        Terra.simulatorService = "store-simulator"

        // Noise in this stack. Each line is a decision somebody defended in review.
        Terra.allowedLogPatterns += listOf(
            Regex("""Connection refused.*retrying"""),
            Regex("""org\.apache\.kafka\.clients\.NetworkClient.*disconnected"""),
        )
    }
}

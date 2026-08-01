package org.cubexmc.update

import org.cubexmc.config.MigrationContext
import org.cubexmc.config.MigrationStep

class OfficialLinkMigrationStep(
    private val from: Int,
    private val to: Int,
) : MigrationStep {
    override fun fromVersion(): Int = from

    override fun toVersion(): Int = to

    override fun description(): String = "Move the default documentation link to the CubeX-MC organization."

    override fun migrate(context: MigrationContext) {
        val current = context.yaml().getString(DOCUMENTATION_PATH)
        if (current == RuleGemsLinks.LEGACY_DOCUMENTATION) {
            context.yaml().set(DOCUMENTATION_PATH, RuleGemsLinks.DOCUMENTATION)
        }
    }

    private companion object {
        const val DOCUMENTATION_PATH = "links.documentation"
    }
}

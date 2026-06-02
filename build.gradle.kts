import com.github.jengelman.gradle.plugins.shadow.transformers.ServiceFileTransformer

plugins { id("cubex-plugin") }

version = "1.0.8"
description = "A Minecraft plugin that grants power through collecting gems"

dependencies {
    compileOnly(CubexDeps.spigotApi("1.16.5-R0.1-SNAPSHOT"))
    compileOnly("com.github.MilkBowl:VaultAPI:1.7")
    implementation(project(":modules:cubex-core"))
    implementation(project(":modules:cubex-config"))
    implementation(project(":modules:cubex-i18n"))
    implementation(project(":modules:cubex-scheduler"))
    implementation(platform("net.kyori:adventure-bom:4.25.0"))
    implementation("org.incendo:cloud-paper:2.0.0-beta.10")
    implementation("org.incendo:cloud-minecraft-extras:2.0.0-beta.10")
    implementation("org.checkerframework:checker-qual:3.43.0")
    implementation("net.kyori:adventure-api")
    implementation("net.kyori:adventure-key")
    implementation("net.kyori:adventure-text-serializer-plain")
    implementation("net.kyori:adventure-text-minimessage")
    implementation("net.kyori:adventure-text-serializer-legacy")
    implementation("net.kyori:examination-api:1.3.0")
    implementation("net.kyori:examination-string:1.3.0")
    implementation("org.jetbrains:annotations:24.1.0")
    implementation("org.apiguardian:apiguardian-api:1.1.2")
    implementation("org.xerial:sqlite-jdbc:3.45.3.0")
    testImplementation("org.junit.jupiter:junit-jupiter:5.9.2")
    testImplementation(CubexDeps.mockitoCore)
    testImplementation("org.mockito:mockito-junit-jupiter:5.11.0")
}

tasks.shadowJar {
    archiveBaseName.set("RuleGems")
    transformers.removeIf { it is ServiceFileTransformer }
    relocate("net.kyori", "org.cubexmc.rulegems.libs.kyori")
    relocate("com.tcoded.folialib", "org.cubexmc.rulegems.libs.folialib")
    relocate("org.incendo", "org.cubexmc.shaded.incendo")
    relocate("io.leangen.geantyref", "org.cubexmc.shaded.geantyref")
}

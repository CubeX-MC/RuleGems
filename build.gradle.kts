import com.github.jengelman.gradle.plugins.shadow.transformers.ServiceFileTransformer
import io.gitlab.arturbosch.detekt.Detekt

plugins {
    id("cubex-kotlin-plugin")
    id("io.gitlab.arturbosch.detekt") version "1.23.8"
    jacoco
}

version = "1.1.0"
description = "A Minecraft plugin that grants power through collecting gems"

dependencies {
    compileOnly(CubexDeps.spigotApi("1.16.5-R0.1-SNAPSHOT"))
    compileOnly("com.github.MilkBowl:VaultAPI:1.7")
    implementation(project(":modules:cubex-core"))
    implementation(project(":modules:cubex-config"))
    implementation(project(":modules:cubex-i18n"))
    implementation(project(":modules:cubex-scheduler"))
    implementation(project(":modules:cubex-database"))
    implementation(project(":modules:cubex-command"))
    implementation(project(":modules:cubex-economy"))
    implementation(project(":modules:cubex-gui"))
    implementation(platform("net.kyori:adventure-bom:4.25.0"))
    implementation("org.incendo:cloud-paper:2.0.0-beta.17")
    implementation("org.incendo:cloud-minecraft-extras:2.0.0-beta.17")
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
    implementation("org.xerial:sqlite-jdbc:3.53.2.0")
    testImplementation("org.junit.jupiter:junit-jupiter:5.9.2")
    testImplementation(CubexDeps.mockitoCore)
    testImplementation("org.mockito:mockito-junit-jupiter:5.11.0")
}

dependencyLocking {
    lockAllConfigurations()
}

tasks.shadowJar {
    archiveBaseName.set("RuleGems")
    transformers.removeIf { it is ServiceFileTransformer }
    relocate("net.kyori", "org.cubexmc.rulegems.libs.kyori")
    relocate("com.tcoded.folialib", "org.cubexmc.rulegems.libs.folialib")
    relocate("org.incendo", "org.cubexmc.shaded.incendo")
    relocate("io.leangen.geantyref", "org.cubexmc.shaded.geantyref")
}

// 钉住 JaCoCo 工具版本:Gradle 8.14.3 的默认值是 0.8.13,而 gradle.lockfile 把它锁在
// {strictly 0.8.11},升 Gradle 时会直接解析失败。锁文件是本插件安全流程的一部分
// (见 .github/workflows/rulegems-security.yml),不该为了跟随 Gradle 默认值就动它。
jacoco { toolVersion = "0.8.11" }

tasks.test {
    finalizedBy(tasks.jacocoTestReport)
}

tasks.jacocoTestReport {
    dependsOn(tasks.test)
    reports {
        xml.required.set(true)
        html.required.set(true)
        csv.required.set(false)
    }
}

detekt {
    buildUponDefaultConfig = true
    baseline = file("detekt-baseline.xml")
    ignoreFailures = false
    parallel = true
}

tasks.withType<Detekt>().configureEach {
    reports {
        html.required.set(true)
        sarif.required.set(true)
        xml.required.set(true)
        txt.required.set(false)
        md.required.set(false)
    }
}

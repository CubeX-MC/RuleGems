# Command argument constraints — verification evidence

## Task

- Date: 2026-08-27.
- Request: configurable owner/appointee transfer amount limits through reusable command input constraints.
- Risk class: R3 (limited-command execution and economy entry points).
- User-visible change: optional `args` constraints and `usage` on `command_allows` list entries.

## Change summary and files

- `src/main/kotlin/org/cubexmc/model/CommandArgumentConstraints.kt`: required inputs, string/number/integer types, inclusive decimal bounds, and structured validation failures.
- `src/main/kotlin/org/cubexmc/manager/CommandArgumentParser.kt`: strict parsing; malformed rules remain attached as an execution-blocking error and log diagnostics.
- `src/main/java/org/cubexmc/model/AllowedCommand.kt`: immutable constraints and usage; the existing four-argument Java constructor remains available.
- `src/main/java/org/cubexmc/manager/GemDefinitionParser.kt`: loads constraints from the command's own power source.
- `src/main/java/org/cubexmc/listeners/CommandAllowanceListener.kt`: checks inputs before counter consumption or cooldown handling on both proxy and preprocess paths.
- `src/main/java/org/cubexmc/manager/CustomCommandExecutor.kt`: also checks direct callers before any part of the execution chain runs.
- Existing model, parser, listener, economy, language and presentation-resource tests extended; no new test harness.
- `README.md`, `README_en.md`, default `powers/powers.yml` comments and both language files updated. Existing language merging supplies missing message keys without overwriting translations.
- No permission, plugin version, transfer opt-in, persistence, automatic quota refill, or cooldown lifecycle changes. No live configuration changed.

## Verification

Targeted command:

```powershell
.\gradlew.bat :RuleGems:test --tests org.cubexmc.model.AllowedCommandTest --tests org.cubexmc.manager.GemDefinitionParserTest --tests org.cubexmc.listeners.CommandAllowanceListenerTest --tests org.cubexmc.manager.EconomySafetyTest --tests org.cubexmc.commands.RuleGemsPresentationResourcesTest --tests org.cubexmc.manager.LanguageManagerModernizationTest :RuleGems:detekt --no-daemon
```

Result: passed; 66 tests, zero failures/errors/skips. Detekt passed without changing its baseline.

Full command:

```powershell
.\gradlew.bat :RuleGems:detekt :RuleGems:test :RuleGems:jacocoTestReport :RuleGems:shadowJar :RuleGems:jarGate --no-daemon
```

Result: passed; 472 tests, zero failures/errors/skips. JaCoCo reports and shaded jar generated.
jarGate: EMBEDDED, unrelocated Kotlin 0, relocated Kotlin 1029, reflection implementation 0,
shared module entries 138, plugin class bytecode major 61, shared bytecode major 61.
`git diff --check -- RuleGems` passed. Existing Gradle 9 deprecation and JVM class-sharing warnings remain.

Artifact: `build/libs/RuleGems-1.1.0.jar`, 8,078,815 bytes.
SHA-256: `FB58AD5E6D14C8E88D2D013BAF220B5721F17274C1E4AD267912AC3077F9DFFC`.
This is a local build, not a deployed or published release.

## Manual regression scope and residual risk

Not run: real Paper/Spigot + CMI/Vault server; tests use mocked economy and Bukkit boundaries.
Before deployment, verify owner 10000 versus appointee 500, inclusive boundary success, over-limit/negative/missing
input rejection, insufficient funds, target-to-bank fines, bank-to-target grants, no failed-transfer broadcast,
and `/rg reload` with both valid and malformed constraints. Confirm bank account identity with the server's provider.

Constraints validate supplied inputs, not template defaults or a chain's cumulative transfer total. Money inputs
should remain required. They do not impose an account-wide limit. Vault transfer compensation and crash risks are
unchanged, and `economy.transfer_directives_enabled` stays opt-in. Usage quotas do not automatically regenerate;
cooldown timestamps still do not survive process restart.

Before rolling back to an older jar that ignores `args`, disable dynamic-amount commands relying on these constraints.

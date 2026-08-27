# Command argument completion — verification evidence

## Task and scope

- Date: 2026-08-27.
- Risk: R3, because completion shares limited-command source/permission resolution and exposes player names.
- Added optional `args.argN.suggestions`: `online_players` or a fixed list of candidates.
- No changes to command execution, economy transfers, permissions, use counters, cooldowns, plugin version, or live configuration.

## Files and behavior

- `CommandArgumentParser.kt` parses suggestion providers; malformed structures keep unknown providers as blocking configuration errors.
- `CommandArgumentConstraints.kt` keeps immutable suggestions and filters fixed values once at configuration load, using the existing type and inclusive bounds.
- `AllowedCommandSuggestions.kt` resolves the current executable power source, matches prefixes, checks Bukkit `Player.canSee`, and returns at most 50 results. It reads online players only for name suggestions, without offline-player scans or direct database/economy calls.
- `CommandAllowanceListener.kt` integrates proxy completion and native Bukkit completion events. Configured results replace native candidates, including empty results; unspecified arguments leave native candidates alone. The proxy's following event does not repeat candidate calculation.
- Existing `GemDefinitionParserTest` and `CommandAllowanceListenerTest` cover YAML parsing, invalid formats, numeric filtering, typed values outside the candidate list, visible players, prefixes, per-source limits, stale label indexes, source changes, trailing spaces, native-result replacement/preservation, result limits, and no side effects.
- `README.md`, `README_en.md`, and the default `powers/powers.yml` example document configuration and rollback. No new language messages or permission nodes are required.

## Automated verification

```powershell
.\gradlew.bat :RuleGems:test --tests org.cubexmc.manager.GemDefinitionParserTest --tests org.cubexmc.listeners.CommandAllowanceListenerTest --tests org.cubexmc.model.AllowedCommandTest :RuleGems:detekt --no-daemon
```

Passed: 59 tests, zero failures/errors/skips; Detekt passed with the existing baseline unchanged.

```powershell
.\gradlew.bat :RuleGems:detekt :RuleGems:test :RuleGems:jacocoTestReport :RuleGems:shadowJar :RuleGems:jarGate --no-daemon
```

Passed: all 482 tests, zero failures/errors/skips. JaCoCo, shadowJar and jarGate passed.
jarGate: EMBEDDED, unrelocated Kotlin 0, reflection implementation 0, plugin/shared bytecode major 61.
`git diff --check -- RuleGems` passed. Existing Kotlin/deprecation/JVM warnings remain.

Artifact: `build/libs/RuleGems-1.1.0.jar`, 8,087,036 bytes.
SHA-256: `B189D915BD15E968EBC165F1093350F9052F9ED98EEEB146BFCBFD1E5EC30A6F`.
Local build only; not deployed or published.

## Remaining runtime checks and rollback

No live server or performance benchmark was run. Check actual client suggestions for proxy and native command
labels, owner versus appointee limits, typing after spaces, join/leave and hide/show changes, and config reload.
Visibility follows Bukkit `canSee`; confirm the server's vanish plugin uses that API.
Other plugins may alter completion events after RuleGems. Completion does not authorize execution or restrict
typed input to its candidate list; the existing execution validator remains authoritative.

Before downgrading to the earlier args-only build, remove `suggestions` fields so its strict parser does not
block commands. Before downgrading to a build without args validation, disable dependent dynamic-amount commands.

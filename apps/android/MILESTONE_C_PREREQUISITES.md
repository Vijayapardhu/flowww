# Milestone C prerequisites — Android companion app

This document records what the Android app layer (Milestone A/B) deliberately does **not** do yet,
why the persistence stack is not Room, the corrected build-toolchain status, and exactly what has to
land before the honest "unavailable" states can be replaced with real behaviour.

## 1. Persistence: why Room was rejected, and the path back

### Why Room was not used

- **Room needs an annotation processor.** Room is compiled by KSP (or kapt). This app builds with
  AGP 8.2.0 + Kotlin 1.9.24 and no KSP plugin, so adopting Room means adding a KSP plugin version
  that matches Kotlin 1.9.24 exactly and a plugin-resolution entry — build-tooling risk before a
  single feature is delivered.
- **The data is three small record shapes.** Staged content metadata, transfer history records and a
  settings blob. The only queries are "list staged items", "list transfer records" and "read
  settings" — no joins, no pagination over a large relational set, which is where Room earns its
  annotation-processor cost.
- **The existing store already has the required semantics.** `StagedStore` and `TransferHistoryStore`
  write atomically (temp file + replace), quarantine corrupt metadata instead of dropping it, and
  keep large bytes out of memory (streamed staging with mid-stream SHA-256). `DataStore` covers the
  settings with process-safe writes and no schema review.
- **Blob bytes never belong in a database.** Staged blobs stay on disk; only metadata is stored, and
  the UI never sees an absolute path or the internal content reference.

### Migration path if Room is adopted later

The domain interfaces are the seam, so no UI or ViewModel changes are required.

1. Keep `StagedContentRepository`, `TransferRepository` and `SettingsRepository`
   (`domain/repository/`) unchanged; only the `data/` implementations are replaced.
2. Add `@Entity` types mirroring the current records: `StagedContentEntity` (`contentId` as primary
   key, type, lifecycle state, display name, MIME, size, source app, timestamps, content reference,
   authorization context) and `TransferRecordEntity` (`id` primary key, source/destination device,
   filename, size, status, route, direction, bytes transferred, error, timestamps, MIME). Keep
   settings in DataStore — a settings blob is not a relational table.
3. Database version 1 with `exportSchema = true`, schemas committed under `app/schemas/` so future
   migrations are reviewable in diffs.
4. One-time importer, shipped **before** the Room-backed repository: on first launch read the existing
   `*.staged` metadata files and the transfer history file, insert the rows, then set a "migration
   complete" flag in DataStore. Entries that fail to parse go to the same `corrupt/` quarantine
   directory the file store uses today — never silently dropped.
5. Cutover order: importer (read-only, writes nothing) → Room-backed repository → remove the file
   loaders only after the importer is verified on a device that holds real staged data.
6. Preserve the current invariants in Room: metadata writes are transactional, and "corrupt metadata
   is quarantined, never discarded" becomes a quarantine table or file rather than a row delete.

## 2. Build toolchain: status and remaining limitations

The v4.3 note claiming *"Gradle build cannot run in this environment due to Java 24 + Gradle 8.11.1
incompatibility; tests compile but cannot execute"* is **historical and no longer true**. Current
state: Gradle wrapper 8.14, daemon on JDK 24.0.2, AGP 8.2.0, Kotlin 1.9.24, Compose UI 1.6.8 —
`assembleDebug`, `testDebugUnitTest`, `lintDebug` and `connectedDebugAndroidTest` all run green.

Remaining limitations that matter for the next milestone:

- **Compose UI test API pinning.** With Compose UI 1.6.8, `assertDoesNotExist()` and `assertExists()`
  are member functions of `SemanticsNodeInteraction`, while `isDisplayed()`, `performClick()`,
  `performScrollTo()` and `performTextReplacement()` are top-level extensions. A Compose upgrade may
  move these; the instrumented suite has to be re-checked after any bump.
- **Instrumented tests need a quiet foreground.** The Compose test rule discovers roots through
  windows that are attached. If another app puts a modal or overlay on screen (observed with a
  social-app modal), the test activity is paused immediately after resuming, its `ComposeView` never
  attaches, and every assertion fails with *"No compose hierarchies found in the app"*. Run
  instrumented tests with the device unlocked, the screen awake, and no overlay app in the
  foreground. This is an environment condition, not an app defect.
- **Java 8 bytecode.** `sourceCompatibility`/`targetCompatibility`/`jvmTarget` remain 1.8 for AGP 8.2
  compatibility; raising them is a separate, deliberate change.
- **Java serialization for persisted records.** `StagedContent` and `TransferRecord` use
  Java serialization with a fixed `serialVersionUID`. It survives restarts but is not a
  schema-stable interchange format; JSON or Protobuf is the follow-up if the format ever needs to
  cross a process or version boundary.
- **Windows-only local development caveat.** `File.renameTo` cannot replace an existing target on
  Windows, and a leaked stream handle blocks a rename, so the stores use
  `Files.move(..., REPLACE_EXISTING)` and close streams before quarantining corrupt metadata. The
  device behaviour is unaffected.

## 3. Honest status of every Rust-dependent action

Nothing in the app fabricates a result. Each of these surfaces the real, current limitation:

| User action | Current behaviour (honest) | What Milestone C must supply |
|---|---|---|
| Send staged content | `ProviderUnavailable` — "Rust transfer engine is not connected"; no transfer record is created | Real `send_content` binding plus transfer records |
| Cancel a transfer | "Cancel is unavailable until the Rust transfer engine is connected." | A live transfer and a real `cancel_transfer` |
| Retry a failed transfer | "Retry is unavailable…" | A real failed transfer to retry |
| Pair with a `flowww://` URI | URI is parsed, validated and expiry-checked, then `PairingResult.Unavailable`; no device becomes trusted | Pairing handshake and the trust store |
| Nearby devices | No peers; discovery reports unavailable | The discovery engine and the peer events |
| Trusted devices | Empty list; rename/forget fail honestly | The persisted, fingerprint-verified trust store |
| Transfer history | Only records produced by a real transfer event; never synthesised | The Rust → Kotlin event bridge |
| Incoming transfer approval | Screen exists, but no offer ever arrives | The offer event and enforced approval policy |
| Gesture → transfer handoff | Diagnostics only; no transfer is ever executed from the diagnostics screen | The controller path wired to a real transfer |
| Settings (device name, gestures, notifications, save location) | Fully functional and persisted locally (DataStore) | Nothing; already real |

## 4. Milestone C prerequisites

1. **UniFFI `.udl` for the Android surface.** Define the Android FFI contract (the desktop side
   already has its own bindings). Suggested location: `apps/android/flowww-rust-android/flowww_android.udl`,
   covering node lifecycle, device info, trust, pairing, discovery, connection, send, cancel and
   transfer status.
2. **A `flowww-rust-android` crate.** A thin crate that wraps the existing workspace crates
   (`flowww-node`, `flowww-transfer`, `flowww-discovery`, `flowww-security`, `flowww-storage`) and
   exposes exactly the functions the `.udl` declares, built as a `cdylib` with the UniFFI scaffolding.
3. **Native ABI builds.** `cargo-ndk` (or an equivalent NDK toolchain) producing `arm64-v8a` and
   `x86_64` shared libraries for debug and release, wired into Gradle through `jniLibs`; the CI
   matrix must build per ABI. The libraries have to be verified on a physical device before any
   honest "unavailable" state is replaced.
4. **An event bridge (Rust → Kotlin).** Callbacks for peer found/lost, transfer updated, offer
   received, pairing completed and errors. The Kotlin side must marshal them onto the main thread
   and feed the repositories — `TransferRepository.recordTransfer` and the trust/discovery flows are
   already shaped to accept real records.
5. **Trust and ingest stores.** The trusted-device list must come from the Rust trust store
   (persisted, fingerprint-verified, supporting rename and forget), and the ingest path must accept
   real incoming content: staged through the same staging store on disk, with the incoming-approval
   policy enforced before anything is accepted.
6. **Real send/cancel binding.** Wire `send_content` and `cancel_transfer` into the repository calls
   that currently fail honestly; the gesture handoff then becomes a controller call rather than a
   stub, and the diagnostics screen can stay strictly diagnostic.
7. **Follow-ups that ride along.** Replace Java serialization with a schema-stable format if records
   ever cross a process boundary, and re-check the Compose UI test API split after any Compose
   upgrade.

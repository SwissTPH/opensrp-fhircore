# Questionnaire edit-text fix and local build changes

Branched from `feature/all-client` (at `d38ab45c4`).

This branch has four groups of changes. Only the first two are real fixes. The last two are
**workarounds to get the `quest` module building locally** and should be reviewed before anything is
merged back.

## 1. Edit-text questions no longer lose keystrokes

### Problem

The SDK's edit-text widget (`EditTextFieldItem` in `com.google.android.fhir.datacapture`) can drop
keystrokes before handing the value to the questionnaire view model:

- Its `snapshotFlow` uses `drop(1)` to skip what it assumes is the initial value. The collector is
  launched on a dispatcher, so a keystroke can arrive before collection starts. That keystroke is
  then the first emission, and it gets dropped.
- Its state is `remember`ed on the `QuestionnaireViewItem`, which is replaced every time the answer
  changes. Each committed keystroke rebuilds the pipeline and opens the same window again.

What users see: typing `180` into a height question that feeds a BMI `calculatedExpression` works
out BMI from `18`. The correct value only arrives when the field loses focus.

### Fix

New package `quest/src/main/java/org/smartregister/fhircore/quest/ui/sdc/edittext/`:

| File | Purpose |
| --- | --- |
| `EditTextCommitPipeline.kt` | Holds the field text and decides when to commit it. It compares against the last committed value instead of dropping by position, debounces by 300 ms (`EDIT_TEXT_COMMIT_DEBOUNCE_MILLIS`), and never overwrites text while the field is focused or has an uncommitted edit. |
| `DebouncedEditTextField.kt` | Compose `OutlinedTextField` backed by the pipeline. Its state is keyed on the question instance, not the view item. Pending edits are flushed on blur and on dispose (view-holder recycling), using the activity's `lifecycleScope` so the flush survives the composable. It keeps the SDK's test tag. |
| `DebouncedEditTextViewHolderDelegate.kt` | Renders the header, item media, text field and unit suffix the same way as the SDK's `EditTextViewHolderDelegate`. The state key is `linkId` + the identity of the response item, so repeated groups stay separate. |
| `DebouncedEditTextViewHolderFactories.kt` | `Decimal`, `Integer`, `SingleLineString` and `MultiLineText` factories that match the SDK ones they replace. They only match plain edit-text questions: no item control, dialog, answer options, answer value set or `answerExpression`. |

The factories are registered **last** in
`QuestionnaireItemViewHolderFactoryMatchersProviderFactoryImpl`. The first matching factory wins,
so the existing OpenSRP widgets (password, barcode, QR code, location) keep priority.

One behaviour change: clearing a **decimal** field now clears the answer. The SDK stores an
unparseable draft instead, which shows a "not a valid decimal" error on an empty field. The integer
factory already cleared the answer, so decimal now matches it.

`quest/build.gradle.kts` now declares `libs.compose.material3` (1.3.2, added to
`libs.versions.toml`) so these widgets compile. It was already on the runtime classpath through the
SDK.

### Tests

`quest/src/test/java/org/smartregister/fhircore/quest/ui/sdc/edittext/EditTextCommitPipelineTest.kt`
covers:

- a keystroke made before collection starts is still committed (the regression)
- the initial value is not committed
- commits happen only after typing pauses
- `flush` commits right away and the debounced commit does not repeat it
- a value normalised by the model (`180` → `180.0`) is picked up
- text still being typed, or in a focused field, is never overwritten

## 2. `QuestionnaireActivity`: no error dialog on a closing window

- `CancellationException` from `launchQuestionnaire()` is rethrown instead of being treated as a
  rendering failure. Backing out mid-launch no longer shows "error loading questionnaire".
- `handleQuestionnaireRenderingFailure` does nothing if the activity is finishing or destroyed. It
  also keeps a reference to the dialog in `alertDialog` so `onDestroy` can dismiss it. Before, the
  dialog could leak its window.

## 3. Workaround: geowidget / Mapbox removed from the build ⚠️

The `:geowidget` module needs a `MAPBOX_SDK_TOKEN` to download dependencies. To build without one:

- `mapbox.gradle.kts` is deleted and no longer applied in the root `build.gradle.kts`.
- `:geowidget` is removed from `settings.gradle.kts` and from `quest`'s dependencies.
- `GeoWidgetLauncherScreen` shows the text "Map view is not available in this build"
  (`R.string.geo_widget_unavailable`) instead of the `GeoWidgetFragment`.
- `quest/.../ui/geowidget/GeoJsonFeature.kt` adds minimal local `GeoJsonFeature` / `Geometry`
  classes to replace the geowidget model types used by `GeoWidgetLauncherViewModel`.

**Effect: the map screen does not work on this branch.** Revert this group before merging if the map
is still needed.

## 4. Workaround: `ADD_RELATED_PERSON` stubbed out ⚠️

`RelatedPersonLinkService` and `quest.ui.relatedperson.RelatedPersonAddCoordinator` are referenced
from `ConfigExtensions.kt` but were never committed on `feature/all-client`, so `quest` did not
compile. For now, `ApplicationWorkflow.ADD_RELATED_PERSON` logs a warning and shows the
`related_person_unable_to_start` toast. Restore the original call once those classes are committed.

## Other build and tooling changes

- `quest/build.gradle.kts`: `OPENSRP_APP_ID` `buildConfigField` is now wrapped in quotes. Before, the
  generated `BuildConfig` held an unquoted identifier instead of a string literal.
- `settings.gradle.kts`: adds the `org.gradle.toolchains.foojay-resolver-convention` (0.10.0)
  plugin. `gradle/gradle-daemon-jvm.properties` (generated by `updateDaemonJvm`) pins the Gradle
  daemon to JetBrains JDK 21.
- `gradle.properties`: adds `org.gradle.tooling.parallel=true` (parallel IDE sync, Gradle 9.4+) and
  `isNonProxy= true`. The second one looks like a local setting and can probably be dropped.
- `ConfigExtensions.kt`: some lines reformatted by ktfmt (KDoc re-wrapping, one chained call
  collapsed onto one line). Behaviour is unchanged.

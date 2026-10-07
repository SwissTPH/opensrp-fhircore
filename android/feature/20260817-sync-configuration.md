# Sync configuration from Settings

| Field | Value |
|-------|-------|
| **Status** | Implemented |
| **Repos** | openSRP FHIRCore Android (`android/`) |
| **Related** | Composition-driven first init (`AppSettingViewModel`) and login `ConfigDownloadWorker` |

Valid status values: `Draft` → `Approved` → `Implemented` → `Superseded`.

---

## Part I — Business spec

### 1. Problem

Manual sync only downloads patient-scoped clinical data. PlanDefinition, Questionnaire,
StructureMap, Library, Measure, Binary, and Parameters are pulled once at first install via the
app `Composition`. After that, picking up a new or updated PD required **Reset data**.

### 2. Goal

A Settings row **Sync configuration** re-downloads Composition-referenced configuration resources
without wiping the local patient database or logging the user out.

### 3. Decisions

| Topic | Decision |
|-------|----------|
| Pipeline | Reuse `ConfigurationRegistry.fetchNonWorkflowConfigResources`, not FHIR Sync. |
| Confirm | Required. Overwriting a Questionnaire / PlanDefinition can leave existing responses and Tasks unmatched. Short warning, Cancel / Sync. |
| Force refresh | User-initiated fetch omits `_lastUpdated`. Login worker stays incremental. |
| After download | Reload in-memory configs, run `DataMigration.migrate()`, then invalidate `ContentCache`. |
| Restart | Reload the current activity after a successful Settings sync. Questionnaires are served from `ContentCache` after the first open; without dropping that cache (and ViewModels) the next form launch still shows the pre-sync resource. Regular FHIR sync also invalidates `ContentCache` so a later form open reads the updated DB. |

---

## Part II — Technical spec

### 4. Where it lives

| Area | Location |
|------|----------|
| Settings option | `SettingsOptions.SYNC_CONFIGURATION` |
| Event + VM | `UserSettingsEvent`, `UserSettingViewModel.syncConfiguration` |
| Confirm + row | `UserSettingScreen` (`ConfirmSyncConfigurationDialog`) |
| Composition fetch | `ConfigurationRegistry.fetchNonWorkflowConfigResources(forceRefresh)` |
| Login worker | `ConfigDownloadWorker` (unchanged, still incremental) |

### 5. End-to-end flow

```text
Settings → Sync configuration
        │  online check
        ▼
Confirm: this may break compatibility with existing data
        │  user confirms
        ▼
fetchNonWorkflowConfigResources(forceRefresh = true)
loadConfigurations(appId, context)   // also clears configsJsonMap
DataMigration.migrate()
ContentCache.invalidate()
        ▼
Toast success, then Activity.refresh() so the next form is not served from cache.
```

### 6. Implementation notes (landed in android)

| Decision | Class / file |
|----------|----------------|
| Settings enum + default on | `ApplicationConfiguration.SettingsOptions` |
| Force-refresh URL (no `_lastUpdated`) | `ConfigurationRegistry.generateRequestBundle` / `fhirResourceDataSourceGetBundle` |
| Parameters actually fetched | `FILTER_RESOURCE_LIST` uses `ResourceType.Parameters.name` |
| Confirm copy | `engine/src/main/res/values/strings.xml` (`sync_configuration_*`) |
| Drop stale Questionnaires | `ContentCache.invalidate()` after Settings sync and after regular FHIR sync |
| Reload UI | `Activity.refresh()` after Settings sync (same helper as language switch) |

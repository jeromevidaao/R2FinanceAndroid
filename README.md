# R2FinanceAndroid

**R2Finance** — multi-account spending register with YNAB-grade categorization (payees, splits, transfers, match, clear/reconcile) **without** envelope budgeting as the product focus.

Companion API/infra: **[R2FinanceAPI](https://github.com/cleaningbutton/R2FinanceAPI)** (API Gateway + Lambda + DynamoDB only).

## Phases

| Phase | What |
|-------|------|
| **1** | Room data model, Compose UI, local CRUD, OTA |
| **2** (in app) | YNAB PAT (encrypted) + full import + balance audit — **More** tab |
| **3** | Bidirectional sync via R2FinanceAPI ↔ YNAB |
| **4** | Cut the cord from YNAB |

### YNAB import (Phase 2)

1. YNAB → Account Settings → Developer Settings → New Personal Access Token  
2. Open **R2Finance → More** → paste token → **Save** → **Import from YNAB**  
3. Review balance audit lines (✓ match / ≠ mismatch)  
4. Token stays on device only (EncryptedSharedPreferences) until Phase 3 server secret

See [docs/PHASED_PLAN.md](docs/PHASED_PLAN.md).

## Not on Play Store

Self-hosted **OTA** (same pattern as R2Android / Cleaning Button):

| Artifact | URL |
|----------|-----|
| Latest APK | `https://www.cleaningbutton.com/r2finance-builds/R2Finance-latest.apk` |
| Version manifest | `https://www.cleaningbutton.com/r2finance-builds/version.json` |
| History | `https://www.cleaningbutton.com/r2finance-builds/history.json` |

CI on `main`: build signed APK → S3 → update `version.json`. In-app **More → Check for updates**.

## Stack

- Kotlin, Jetpack Compose, Room, Navigation
- Money as **milliunits** (`Long`), YNAB-compatible
- Sync metadata on every entity (`ynabId`, `syncStatus`, `updatedAt`, soft `deleted`)

## Local build

```bash
./gradlew testDebugUnitTest assembleDebug
```

## Package

- `applicationId`: `com.cleaningbutton.r2finance`
- App name: **R2Finance**

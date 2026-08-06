# R2FinanceAndroid

**R2Finance** — multi-account spending register with YNAB-grade categorization (payees, splits, transfers, match, clear/reconcile) **without** envelope budgeting as the product focus.

Companion API/infra: **[R2FinanceAPI](https://github.com/cleaningbutton/R2FinanceAPI)** (API Gateway + Lambda + DynamoDB only).

## Data flow

```
Android (Room)  ◄── HTTPS ──►  R2FinanceAPI + DynamoDB  ◄── backend only ──►  YNAB
```

- **This app never calls the YNAB API.** No PAT, no on-device import.
- **Offline-first:** UI always reads/writes **Room**. Works for hours with zero network.
- Local edits → `PENDING_PUSH` → auto **device push to DynamoDB** when online
  (`POST /v1/device/push` via `ConnectivityMonitor`).
- Pull DDB → Room after push; **YNAB is backend-only** (EventBridge ~15m / sync tick).
- Navigate away/back does **not** re-download. Manual Accounts → Sync = push + pull + YNAB tick.
- Pull preserves local `PENDING_PUSH` rows (phone edits not clobbered by cloud snapshot).
- **In-memory aggregates:** `LedgerAggregatesStore` precomputes Reflect / Spending Breakdown /
  Home totals / account balances on a background dispatcher at process start and after ledger
  changes (debounced). UI screens collect the snapshot — no main-thread full-ledger scans.

## Phases

| Phase | What |
|-------|------|
| **1** | Room data model, Compose UI, local CRUD, OTA |
| **2** | ~~On-device YNAB import~~ **removed** — import/sync is server-side only |
| **3** | Bidirectional sync via R2FinanceAPI ↔ YNAB (backend) |
| **3b** | **Categorize spending** on Inbox + Register (tap row / Categorize) → API → YNAB; YNAB categories pull back into Room |
| **3c** | **Reports** — YNAB-style analytics: month / year / all-time, by category·group·payee·account, income vs expense trends |
| **4** | Cut the cord from YNAB on the backend |

See [docs/PHASED_PLAN.md](docs/PHASED_PLAN.md).

## Not on Play Store — self-hosted OTA

Same pattern as R2Android / Cleaning Button:

| Artifact | URL |
|----------|-----|
| Latest APK | `https://www.cleaningbutton.com/r2finance-builds/R2Finance-latest.apk` |
| Version manifest | `https://www.cleaningbutton.com/r2finance-builds/version.json` |
| History | `https://www.cleaningbutton.com/r2finance-builds/history.json` |

**How it works**

1. Push to `main` → CI runs unit tests + builds **stable-signed** debug APK (SSM keystore).
2. CI uploads `R2Finance-latest.apk` + `version.json` (+ `history.json`) to S3 (`cleansite/r2finance-builds/`).
3. App checks `version.json` after login (**UpdateGate**) and from **More → Check for updates**.
4. If remote `versionCode` is higher → dialog → download → package installer.

**Requirements for CI OTA**

- Repo secret `AWS_ROLE_ARN` = `arn:aws:iam::834917996497:role/github-actions-cleaningbutton-deploy`
- OIDC trust includes `repo:jeromevidaao/R2FinanceAndroid:*`
- SSM params under `/android/cleaningbutton/*` (or `/android/r2finance/*`)

Bump `versionCode` in `app/build.gradle.kts` on every shippable change (CI fails if not greater than published).

## Stack

- Kotlin, Jetpack Compose, Room, Navigation
- Money as **milliunits** (`Long`)
- Sync metadata on every entity (`ynabId` stable remote id, `syncStatus`, `updatedAt`, soft `deleted`)

## Local build

```bash
./gradlew testDebugUnitTest assembleDebug
```

## Package

- `applicationId`: `com.cleaningbutton.r2finance`
- App name: **R2Finance**

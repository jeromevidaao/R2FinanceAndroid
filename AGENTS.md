# R2FinanceAndroid — agent notes

- Product: **R2Finance** (spend tracking, not envelope budgeting).
- Companion: **R2FinanceAPI** (Lambda + DDB + API GW only).
- **Android never calls YNAB.** Cloud only: Room ↔ R2FinanceAPI (DDB). YNAB sync is backend-only until cutover.
- **Offline-first:** UI always observes/writes Room. `PENDING_PUSH` queue flushes to DDB on reconnect (`ConnectivityMonitor` + `POST /v1/device/push`). YNAB is backend-only later. Never re-pull full ledger on every Accounts visit.
- **Paint cache + hydrate once:** process-scoped `InboxCache`, `LedgerAggregatesStore`, `ConnectorsCache` paint Home / Categorization / Accounts / Reflect from RAM. `ensureHydrated` runs at process warmup (+ reconnect), **not** on bottom-nav enter. Connectors warm after auth (`warmAuthenticatedCaches`). Manual Accounts refresh only.
- **Categorization pull-to-refresh:** swipe down (or toolbar refresh) → `SyncCoordinator.refresh` (push + delta + tick) then `pullInbox`; list stays painted from RAM/Room.
- **Reflect spending = YNAB net activity:** `Analytics.buildSpendingReport` totals must match YNAB month/Reflect Total spending: exclude transfers; include unapproved; **net** refunds/returns in spending categories (do not treat them as income); only `Inflow: Ready to Assign` counts as income. Never use gross-outflow-only for Total spending. Pure-transfer splits (legs sum to parent) must not fall back to parent amount; **orphan** transfer-only legs that do not reconcile must fall back to parent (and cloud sync must replace local subs, never accumulate).
- **Home Accounts total:** prefer `AccountEntity.balanceMilli` from cloud/YNAB (same as website `a.balance`). Summing Room transactions is a fallback only when balance has never been synced.
- Keep entity field `ynabId` — it is the stable remote/DDB key name from the API, not an on-device YNAB client.
- **Always commit + push** after finished work; watch CI green before claiming OTA shipped.
- OTA prefix: `r2finance-builds/` — not Play Store.
- Never commit YNAB PATs or keystores.

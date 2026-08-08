# R2FinanceAndroid — agent notes

- Product: **R2Finance** (spend tracking, not envelope budgeting).
- Companion: **R2FinanceAPI** (Lambda + DDB + API GW only).
- **Android never calls YNAB.** Cloud only: Room ↔ R2FinanceAPI (DDB). YNAB sync is backend-only until cutover.
- **Offline-first:** UI always observes/writes Room. `PENDING_PUSH` queue flushes to DDB on reconnect (`ConnectivityMonitor` + `POST /v1/device/push`). YNAB is backend-only later. Never re-pull full ledger on every Accounts visit.
- **Paint cache + hydrate once:** process-scoped `InboxCache`, `LedgerAggregatesStore`, `ConnectorsCache` paint Home / Categorization / Accounts / Reflect from RAM. `ensureHydrated` runs at process warmup (+ reconnect), **not** on bottom-nav enter. Connectors warm after auth (`warmAuthenticatedCaches`). Manual Accounts refresh only.
- Keep entity field `ynabId` — it is the stable remote/DDB key name from the API, not an on-device YNAB client.
- **Always commit + push** after finished work; watch CI green before claiming OTA shipped.
- OTA prefix: `r2finance-builds/` — not Play Store.
- Never commit YNAB PATs or keystores.

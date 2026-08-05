# Ledger — multi-week phased plan

**Product:** Multi-account spending register with YNAB-grade categorization (payees, splits, transfers, match, clear/reconcile) and reports. Envelope budgeting is out of scope.

**End state:** Fully independent of YNAB, data in AWS (DynamoDB) + Android, optional cloud API.

---

## Architecture (target)

```
┌─────────────────┐     sync      ┌──────────────────┐     sync      ┌─────────────┐
│  Android app    │ ◄──────────► │  AWS (API + DDB) │ ◄──────────► │  YNAB API   │
│  Room (local)   │   HTTPS      │  source of truth │   REST       │  (temporary)│
└─────────────────┘              └──────────────────┘              └─────────────┘
```

- **Phase 1–2:** Room is primary. **On-device YNAB import was removed** — full import is server-side (R2FinanceAPI).
- **Phase 3:** DDB is canonical; Android syncs only with AWS; YNAB (if any) syncs only via backend Lambdas.
- **Phase 4:** YNAB connector disabled on AWS; DDB + Android only.

Every local row carries sync metadata so dual-write is possible without rewrite:

| Field | Purpose |
|-------|---------|
| `id` | Ledger UUID (stable forever) |
| `ynabId` | YNAB resource id while linked |
| `updatedAt` | Local wall clock / logical update |
| `serverKnowledge` | Per-plan YNAB delta cursor (plan-level) |
| `syncStatus` | `SYNCED` / `PENDING_PUSH` / `CONFLICT` / `LOCAL_ONLY` |
| `deleted` | Soft delete (YNAB-compatible tombstones) |

Money is always **milliunits** (`Long`), never floating point.

---

## Phase 1 — Data model + Android app (this week)

**Goal:** Runnable app with YNAB-aligned schema, empty local DB, core navigation.

### Deliverables
- [x] Project `ledger-android` (Compose, Room, Hilt-ready / manual DI)
- [x] Room entities: Plan, Account, CategoryGroup, Category, Payee, Transaction, SubTransaction, ScheduledTransaction, PayeeCategoryMemory
- [x] DAOs + repositories for CRUD and derived balances
- [x] UI shells: Accounts, Register, Categories, Inbox (uncategorized/unapproved)
- [x] Domain rules stubs: milliunits, soft delete, transfer fields
- [ ] Manual add/edit transaction (minimal) — next commit in Phase 1
- [ ] Unit tests for money + split integrity

### Out of scope
- YNAB network calls, DDB, bank import

---

## Phase 2 — Migrate everything from YNAB (server-side only)

**Goal:** One-shot (re-runnable) full import of live YNAB plan into **DynamoDB** via R2FinanceAPI. Android hydrates from cloud only.

### Android
- **No YNAB client, PAT, or import UI** (removed — confusing dual path).
- Pull ledger via `CloudSync` / Accounts → Sync from cloud.

### Backend (R2FinanceAPI)
- PAT in Secrets Manager (`R2Finance/ynab-pat`)
- Full import + delta pull + push Lambdas
- Preserve YNAB ids as stable remote keys (`ynabId` on API/DDB rows)

### Success criteria
- Balances per account match YNAB within 1 milliunit (server import audit)
- Re-import is idempotent on remote id
- Android can fully populate Room from DDB without ever talking to YNAB

---

## Phase 3 — Bidirectional sync (YNAB ↔ DDB ↔ Android)

**Goal:** Categorize or create categories/transactions in either YNAB or Ledger; both converge via AWS.

### Components
1. **DynamoDB tables** (suggested)
   - `ledger-plans`, `ledger-accounts`, `ledger-categories`, `ledger-payees`, `ledger-transactions`, `ledger-sync-cursors`
   - PK/SK design: `PLAN#uuid` / `TXN#uuid`, GSI on `ynabId`, GSI on `updatedAt`
2. **Sync Lambda(s)**
   - **Pull YNAB → DDB:** delta poll (`last_knowledge_of_server`), map patch/create/delete
   - **Push DDB → YNAB:** pending mutations (create/update category, categorize txn, create txn)
   - **Android ↔ DDB:** REST/AppSync; device sends pending local mutations; pulls remote changes
3. **Conflict policy (v1)**
   - Last-writer-wins on `updatedAt` (with YNAB `server_knowledge` as secondary for YNAB-origin rows)
   - Field-level: category_id / approved / memo can merge if only one side touched
   - Conflicts surface in Inbox with “keep Ledger / keep YNAB”
4. **What syncs both ways**
   - Categories & category groups (create/rename/hide)
   - Transactions: create, categorize, approve, memo, flag, clear status
   - Payees (create/rename)
   - Soft deletes where API allows
5. **What is one-way or deferred**
   - Bank direct import (YNAB owns) → pull only
   - Money movements / goals / RTA → ignore
   - Reconcile markers — local + DDB; push cleared status if API allows

### Rate limits & reliability
- Queue pushes (SQS) to stay under 200 req/h
- Exponential backoff on 429
- Idempotent writes via `import_id` / `ynabId`

### Success criteria
- Change category on phone → appears in YNAB within poll interval
- Change category in YNAB → appears on phone after sync
- New category either side propagates
- No duplicate transactions on re-sync

---

## Phase 4 — Cut the cord

**Goal:** Stop all YNAB API traffic; Ledger + DDB is system of record.

### Deliverables
- Feature flag `ynabSyncEnabled=false`
- Strip or archive YNAB connector Lambda
- Clear PAT from devices/AWS
- Final verification export (CSV) from both for audit
- Optional: cancel YNAB subscription after N days of dual-run confidence
- Docs: backup/restore from DDB only

### Success criteria
- App fully usable offline-first + cloud sync without YNAB
- Historical data complete
- No dependency on YNAB in CI or runtime

---

## Suggested calendar (flexible)

| Week | Focus |
|------|--------|
| 1 | Phase 1: schema, Room, shell UI, local CRUD |
| 2 | Phase 1 finish: edit txn, splits, transfers; tests |
| 3 | Phase 2: server YNAB → DDB import; Android cloud pull only |
| 4–5 | Phase 3: DDB schema, pull sync, Android cloud API |
| 6–7 | Phase 3: push mutations both ways, conflicts, polish |
| 8 | Phase 4: dual-run soak, cutover checklist, disable YNAB |

---

## Compliance note

YNAB API Terms restrict using the API to **duplicate YNAB as a product**. This project is a **personal migration + temporary bridge** to an independent spend tracker, not a redistributed YNAB clone or multi-tenant “YNAB alternative” service. Do not publish OAuth app as a general YNAB client that replaces the official app. Personal Access Token for own plan is the intended Phase 2–3 path.

---

## Phase status

| Phase | Status |
|-------|--------|
| 1 Data model + app | **In progress** |
| 2 Migrate from YNAB | Not started |
| 3 Bidirectional sync | Not started |
| 4 Cut cord | Not started |

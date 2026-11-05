---
name: obens-integration
description: Integrate an existing platform (its database and backend) with Obens using the Obens SDK (JavaScript, Python, PHP or Java). Use when the user wants to push their farmers/sub-actors and facilities to Obens, keep them in sync, act for delegated accounts, or asks how to wire the Obens SDK into their app. Not for the Obens dashboard or the core API itself.
---

# Integrate a platform with Obens

Goal: the user's platform keeps its own database and UI; a **single integration file** in the backend pushes every
change to Obens. The reference implementation is in this repository, in four languages. **Read the one that matches
the user's stack before writing code**, and copy its shape:

| Language | Integration file (read this first) | Mapper | Account / list lookup |
|---|---|---|---|
| JavaScript | `obens-demo-js/src/integration.js` | `src/mapping.js` | `src/config.js` |
| Python | `obens-demo-python/app/integration.py` | `app/mapping.py` | `app/config.py` |
| PHP | `obens-demo-php/src/Integration.php` | `src/Mapping.php` | `src/Config.php` |
| Java | `obens-demo-java/src/main/java/so/obens/demo/Integration.java` | `Mapping.java` | `Config.java` |

The demo's platform (`db`, `server`, `public/index.html`) is deliberately Obens-free: do the same, do not leak Obens
types into the user's models, routes or UI.

## Vocabulary (use the user's words in their code, these in the integration)

- **Account**: a company of the supply chain. An API key belongs to ONE account. Other accounts can **delegate** to it;
  the key then acts for them with `forAccount` / `for_account` (adds `?account-id=`). Each account has its own
  sub-actors, facilities, lists and **id space**.
- **Element**: a list of the supply chain ("Fresh Mango"). A sub-actor is created INSIDE one list. Each account only
  sees its own lists, so look the list up with the client that acts as that account.
- **Sub-actor** = Obens **farmer** (`type: FARMER`). **Facility** = a place; owned by a sub-actor
  (`subActorExternalId`) or by the account (no owner).
- **externalId**: the USER's own id for a record. Pushing the same id again updates, never duplicates.
- **Event / mapper / syncer**: the SDK's mappers read `{ type, action, data }`; a syncer (`subActors` or `facilities`)
  offers `init(list)` (bulk, returns a report), `push(event)`, `remove(event)` (soft delete).

## Workflow

1. **Understand the user's data.** Find their sub-actor-like and facility-like tables and how rows are identified.
   Decide what the stable external id is (their primary key, as a string). Ask only what you cannot see: which Obens
   account the key belongs to, whether other accounts delegate to it, and which **list** each account's sub-actors go in.
   Never assume a list: the user chooses it (by name), there is no default.
2. **Install the SDK** for their language (see the SDK repo README: `Obens-Ltd/obens-sdk-js|python|php|java`).
   Config: `OBENS_API_KEY` (required, secret, never in code or the UI), `OBENS_BASE_URL` (default
   `https://api.obens.so/v2/core`). Without a key the platform must still run and save, just without syncing.
3. **Write `integration` as one file**, in the demo's four parts, each line commented with WHY:
   1. *Rows to events*: one function per record type building the event the mapper reads. A facility with an owner
      is an orchard-type event (`growerId` -> `subActorExternalId`); without an owner it is a site.
   2. *One target per account*: find the account among `accounts.accessible()` by name or id, get the client that acts
      as it (the key's own account needs no switch), find its list by name, then create the syncers
      (sub-actors with the grower mapper bound to the list id, facilities for owned and unowned). Cache per account.
   3. *Initial load at boot*: `init` sub-actors first, then owned facilities, then sites (a facility's owner must exist
      first). Log the report: total / created / updated / failed and each failure. Run it AFTER the server is listening
      or in the background: never block startup or a health probe on network calls.
   4. *After every change*: `push` after create/update, `remove` after delete. Delete a sub-actor's facilities first.
4. **Mappers** (see `mapping` file): sub-actor -> `externalId`, `name`, `ref` (phone: tells apart equal names),
   `address`, `gender` (`MALE`/`FEMALE`, unknown values left OUT not sent as null), `type: FARMER`, `elementId`.
   Facility -> `externalId`, `info.name`, `info.description`, `coordinates` (3+ outline points = closed `Area`
   polygon in GeoJSON `[longitude, latitude]`, otherwise a `Point`), `subActorExternalId`. Only send what the user's
   data really has; tolerate missing fields.
5. **Hook the backend**: call the four integration methods right after the database write in the create / update /
   delete handlers. Do not change the user's routes, schema or UI beyond that.
6. **Verify**: run the user's tests; unit-test the mappers with real rows (same name different phone stays two
   farmers; unknown gender omitted; polygon ring closed; owner id passed through). If the user has a key, run an
   initial load and show the report; a second run must report `updated`, not `created`.

## Rules that matter

- **A sync failure never blocks the user's save.** Catch, log (`[sync] ... failed: reason`), move on; the next
  start-up `init` reconciles. Ids make re-sending safe.
- **Delete is soft** (`remove`): sub-actor gets status `DELETED`, facility `deleted: true`. Do not promise erasure.
- **Facility ids are unique per owner.** Read one back with its owner (`get(id, subActor=owner)`); never change an
  existing facility's owner (it is part of its identity): make a new one.
- **Ids are per account.** The same `grw-001` in two accounts is two farmers; always act through the right account's
  client.
- **Delegations can be revoked**: a call as that account then fails with `403`, and it disappears from
  `accounts.accessible()`. Skip it with a message instead of crashing.
- **Stateless runtimes (PHP)**: no cache between requests, so account and list lookups repeat per change; run the
  initial load as a separate script (`bin/sync.php`), as the PHP demo does.
- Secrets: never print or commit the key. Do not log request bodies with personal data beyond ids.
- Do not invent SDK methods. If unsure of a signature, read the SDK source in the user's dependency (or its README)
  and the demo's integration file; they are the source of truth.

## Done when

The user can add, edit and delete a sub-actor and a facility in their own app and see the matching `[sync] ...
CREATED/UPDATED` lines (and the records in Obens), the platform still works with no key set, and the whole Obens
integration is readable in one file.

# Mango Network: a demo platform kept in sync with Obens (Java)

A tiny platform with **its own database, dashboard and API**: four companies of the *Dried Mango* supply chain, each
with its **sub-actors** (people it works with) and **facilities** (places). Add, edit or delete them in the dashboard
and the backend pushes every change to Obens with the [Java SDK](https://github.com/Obens-Ltd/obens-sdk-java).
The dashboard itself shows nothing about Obens: it is "your" platform, the integration is only in the backend.

## Run it with Docker

```bash
docker compose up --build                       # the platform alone, with its sample data
OBENS_API_KEY=obk_... docker compose up --build # the same, and kept in sync with Obens
```

Open **http://localhost:3000**. Your changes are kept in the `platform-data` volume
(`docker compose down -v` resets them). Without a key nothing is sent and the log says so; with one, the log shows
every call (`[sync] sub-actor grw-003 (mango-processor): CREATED`).

Without Compose: `docker build -t mango-java . && docker run --rm -p 3000:3000 -e OBENS_API_KEY=obk_... -v mango-java-data:/data mango-java`.

### Without Docker

You need Java 17+ and Maven.

```bash
OBENS_API_KEY=obk_... mvn -q compile exec:java
```

then open http://localhost:3000. The SDK is fetched through [JitPack](https://jitpack.io). JitPack cannot build tag `v0.1.3` yet (its default JDK is 8, the SDK needs 17), so the Dockerfile builds the tag itself; `mvn` outside Docker needs the SDK in your local repository (`mvn install` it from its repo) until a tag carrying a `jitpack.yml` with `jdk: [openjdk17]` exists.

`mvn test` runs the mapping tests, offline.

## What is in the database

On the first start the database is filled from [`data/seed.json`](data/seed.json): one block per account, with the
**list** (element) its sub-actors belong to.

| Account | Its list | Sub-actors | Facilities |
|---|---|---|---|
| **Mango Cooperative** | Fresh Mango | 4 growers | 3 orchards (one per grower, one grower has two) + a collection point |
| **Mango Processor** | Sliced Mango | 2 contract slicers | a slicing station each + a slicing unit and a drying plant |
| **Mango Exporter** | Dried Mango | 1 packer | their packing point + the packing house |
| **Mango Importer** | Packaged Dried Mango | 1 retailer | their shop + a warehouse |

**Mango Importer owns the API key**; the other three accounts delegated their account to it, so one key writes
for all four. A facility **with an owner** belongs to that sub-actor (an orchard); a facility **without** belongs to the
account itself (a site).

## The words used here

| Word | Meaning |
|---|---|
| **Account** | A company of the supply chain (Mango Cooperative...). It has its own sub-actors, facilities and lists. |
| **Key** (`OBENS_API_KEY`, `obk_...`) | Belongs to one account. Created in *Settings → API keys*. The secret is shown once. |
| **Delegated account** | An account that let your key act for it. It can revoke that at any time (calls then fail with `403`). |
| **Element** (a "list") | A step of the supply chain: *Fresh Mango*, *Sliced Mango*... Each account only sees its own lists. A sub-actor is created **in** one list. |
| **Sub-actor** | A person or small business an account works with. Obens calls it a **farmer**. |
| **Facility** | A place. Owned by a sub-actor, or by the account itself. |
| **`externalId`** | **Your** id for a record (`grw-001`). Sending the same id twice **updates**, never duplicates. |
| **Id space** | Each account has its own: `grw-001` of the Cooperative and `grw-001` of the Processor are two farmers. |
| **Event** | `{ type, action, data }`: the small object the SDK's mappers read. You build it from a database row. |
| **Mapper** | The few lines that turn your record (`firstName`, `lastName`) into Obens' (`name`). |
| **Syncer** | `init` loads a whole table with a report, `push` sends one change, `remove` deletes (soft: Obens keeps the record). |
| **Outline** | A facility traced on the map: 3+ `lat,lng` points make a polygon. Otherwise a single position. |

## How the integration works

Everything the SDK does is in **`src/main/java/so/obens/demo/Integration.java`**, written to be read top to bottom with a comment on every call.
The rest of the project never touches Obens.

| File | What it is |
|---|---|
| `App.java` | the REST API and the page (JDK `HttpServer`) |
| `Db.java` | the database: tables, queries, first-start seed (SQLite over JDBC) |
| `Integration.java` | **the Obens integration: everything the SDK does** |
| `Mapping.java` | your record → Obens payload (the mappers) |
| `Config.java` | connect, find an account, find a list |
| `public/index.html` | the dashboard (at the project root) |
| `data/seed.json` | the first-start data (at the project root) |

The integration has four parts:

1. **Rows → events.** `subActorEvent` / `facilityEvent` build the small event the mappers read. This is the only code you write per record type.
2. **One target per account.** For each account: find it among the accounts the key can act on, get a client that acts **as** it (`forAccount`), look its list up **by name** (`"Fresh Mango"`), and create the syncers.
3. **At boot: the initial load.** `init` sends every sub-actor, then every orchard, then every site, and logs a report (`created` / `updated` / `failed`). Sub-actors go first because an orchard needs its owner to exist.
4. **After each change.** `push` after an add or an edit, `remove` after a delete.

| In the dashboard | The backend saves it, then calls |
|---|---|
| Add / edit a sub-actor | `subActors.push(event)`: created in the account's list, keyed by its id |
| Delete a sub-actor | its facilities first, then `subActors.remove(event)` (soft delete: status `DELETED`) |
| Add / edit a facility **with an owner** | `orchards.push(event)`: the facility is created for that sub-actor (`subActorExternalId`) |
| Add / edit a facility **without** an owner | `sites.push(event)`: a facility of the account |
| Delete a facility | `remove(event)` (soft delete: `deleted: true`) |

A sync failure is logged and **never blocks the save**: your platform must not depend on Obens being reachable. The
next start sends everything again, so anything missed is reconciled (the ids make it safe).

### How a sub-actor is mapped

| Your row | Obens | |
|---|---|---|
| `id` | `externalId` | your id, the key of the mapping |
| `firstName` + `lastName` | `name` | |
| `phone` | `ref` | tells two people with the same name apart |
| `village` | `address` | |
| `gender` `M` / `F` | `gender` `MALE` / `FEMALE` | anything else is left out |
| | `type` = `FARMER`, `elementId` = the account's list | constants |

### How a facility is mapped

| Your row | Obens | |
|---|---|---|
| `id` | `externalId` | |
| `name` | `info.name` | |
| `variety`, `plantedOn` / `description` | `info.description` | an owned facility sends variety and planting date, a site its description |
| `outline` (3+ points) | `coordinates` as an `Area` polygon | GeoJSON `[longitude, latitude]`, ring closed |
| `latitude`, `longitude` | `coordinates` as a `Point` | when there is no outline |
| `subActorId` | `subActorExternalId` | the owner: a facility id is unique **per owner** |

## The API of the platform

| | |
|---|---|
| `GET /api/accounts` | the accounts, with their counts |
| `GET` `POST` `/api/accounts/{account}/sub-actors` | list, add |
| `PUT` `DELETE` `/api/accounts/{account}/sub-actors/{id}` | edit, delete (its facilities go too) |
| `GET` `POST` `/api/accounts/{account}/facilities` | list, add (`subActorId` = owner, empty = a site of the account) |
| `PUT` `DELETE` `/api/accounts/{account}/facilities/{id}` | edit (the owner never changes), delete |

```bash
curl -X POST localhost:3000/api/accounts/mango-cooperative/sub-actors \
  -H 'content-type: application/json' -d '{"firstName":"Awa","lastName":"Diallo","gender":"F","village":"Banfora"}'
```

| Variable | Default | |
|---|---|---|
| `OBENS_API_KEY` | | without it the platform runs alone, nothing is sent |
| `OBENS_BASE_URL` | `https://api.obens.so/v2/core` | |
| `PORT` | `3000` | inside the container it is always 3000, Compose maps it to 3000 on your machine |
| `DB_PATH` | `data/platform.db` (`/data/platform.db` in Docker) | the SQLite file |

## License

MIT, see [LICENSE](LICENSE).

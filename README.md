# Obens SDK demos

The same demo in four languages. Each one is **Mango Network**, a tiny platform with its **own database, dashboard and
API** whose backend keeps [Obens](https://obens.so) in sync with the matching SDK. The dashboard shows nothing about
Obens: it is "your" platform, and the whole integration is one commented file in the backend.

| Language | Folder | SDK | Integration file |
|---|---|---|---|
| JavaScript | [`obens-demo-js`](obens-demo-js) | [obens-sdk-js](https://github.com/Obens-Ltd/obens-sdk-js) | `src/integration.js` |
| Python | [`obens-demo-python`](obens-demo-python) | [obens-sdk-python](https://github.com/Obens-Ltd/obens-sdk-python) | `app/integration.py` |
| PHP | [`obens-demo-php`](obens-demo-php) | [obens-sdk-php](https://github.com/Obens-Ltd/obens-sdk-php) | `src/Integration.php` |
| Java | [`obens-demo-java`](obens-demo-java) | [obens-sdk-java](https://github.com/Obens-Ltd/obens-sdk-java) | `src/main/java/so/obens/demo/Integration.java` |

## Run one

```bash
cd obens-demo-js                                   # or -python, -php, -java
docker compose up --build                          # the platform alone, with its sample data
OBENS_API_KEY=obk_... docker compose up --build    # the same, and kept in sync with Obens
```

Open **http://localhost:3000**. All four demos use that port, so run one at a time (`docker compose down` before
starting another). Without a key nothing is sent and the log says so; with one, the log shows every call
(`[sync] sub-actor grw-003 (mango-processor): CREATED`). Changes are kept in a Docker volume;
`docker compose down -v` resets them.

An API key is created in the platform under **Settings → API keys → Create key**, on the account that should own it
(here, *Mango Importer*). The secret (`obk_...`) is shown once.

## What the demo shows

The *Dried Mango* supply chain, from the orchard to the shelf, with four companies (**accounts**):

| Account | Its list (element) | What it has in the database |
|---|---|---|
| **Mango Cooperative** | Fresh Mango | growers (sub-actors), their orchards (facilities) and a collection point |
| **Mango Processor** | Sliced Mango | contract slicers, a station each, a slicing unit and a drying plant |
| **Mango Exporter** | Dried Mango | a packer, a packing point and the packing house |
| **Mango Importer** | Packaged Dried Mango | a retailer, a shop and a warehouse. **It owns the API key** |

The Cooperative, the Processor and the Exporter **delegated** their account to the Importer, so one key writes for
all four. In the dashboard you add, edit and delete **sub-actors** (Obens calls them *farmers*) and **facilities**
(owned by a sub-actor, or by the account itself); the backend pushes each change with the SDK:

- the first start loads everything (`init`, with a report: created / updated / failed);
- an add or an edit is a `push`, keyed by **your own id** (`externalId`): sending a record twice updates it, never duplicates it;
- a delete is a `remove` (soft: Obens keeps the record);
- a sync failure is logged and never blocks the save.

Each demo's README explains the vocabulary, how every field is mapped, and the platform's REST API.

## Layout

Every demo has the same shape:

```
data/seed.json        the first-start data (accounts, sub-actors, facilities)
public/index.html     the dashboard (identical in the four demos)
<server>              the REST API and the page
<db>                  the SQLite database
<integration>         everything the SDK does, a comment on every call
<mapping>             your record -> Obens payload
Dockerfile, docker-compose.yml
```

## Claude Code skill

[`.claude/skills/obens-integration`](.claude/skills/obens-integration/SKILL.md) teaches Claude Code to add this kind of
integration to **your** platform: where your sub-actors and facilities map, how to pick accounts and lists, the initial
load, then `push` / `remove` after each change. It is picked up automatically when you open this repository in Claude
Code; to use it in your own project, copy the folder to `.claude/skills/` there (or `~/.claude/skills/` for all projects).

## Notes

- **Java:** JitPack cannot build the Java SDK tag yet (its default JDK is 8, the SDK needs 17), so the Java Dockerfile
  builds the tag itself. Plain `mvn` outside Docker needs the SDK in your local Maven repository until the SDK repo has a
  `jitpack.yml` with `jdk: [openjdk17]`.
- **PHP:** the built-in server cannot run a task beside the requests, so the initial load runs as `bin/sync.php`
  next to the server (the Dockerfile starts both).

## License

MIT.

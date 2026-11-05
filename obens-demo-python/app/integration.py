"""THE WHOLE OBENS INTEGRATION LIVES IN THIS FILE.

The platform (db.py, server.py, public/) knows nothing about Obens. It only calls the functions of ``Integration``
after it has saved a change (push_sub_actor / remove_sub_actor / push_facility / remove_facility), plus start() once at
boot. Read the file top to bottom: every SDK call is explained.

Vocabulary (see the README for more):
  account      a company of the supply chain ("Mango Cooperative"). The API key belongs to ONE account (the importer);
               the others "delegated" their account to it, so the key can also act for them.
  element      a list of the supply chain ("Fresh Mango"). A sub-actor is created INSIDE one list.
  sub-actor    a person the account works with. Obens calls it a "farmer" (type FARMER).
  facility     a place. Owned by a sub-actor (an "orchard") or by the account itself (a "site").
  external id  YOUR id for a record ("grw-001"). Obens remembers it, so sending the same record twice UPDATES it.
"""
import threading

from app.config import acting_as, find_element
from app.mapping import grower_mapper, orchard_mapper, site_mapper


# --------------------------------------------------------------------------------------------------------------
# 1. Your rows -> events.
#    The SDK's mappers read a small "event" ({type, action, data}). Building it from a database row is the only
#    thing you write per record type; the mapper (mapping.py) then turns the event into the Obens payload.
# --------------------------------------------------------------------------------------------------------------

def sub_actor_event(s):
    """A sub-actor row. ``appId`` is YOUR id: the mapper sends it as the external id."""
    return {"type": "grower", "action": "create",  # push() only needs the data: "create" and "update" are the same upsert
            "data": {"appId": s["id"], "firstName": s["firstName"], "lastName": s["lastName"],
                     "phone": s["phone"], "gender": s["gender"], "village": s["village"]}}


def facility_event(f):
    """A facility row. With an owner it is an "orchard" (a facility OWNED by that sub-actor), without one a "site"."""
    if f["subActorId"]:
        return {"type": "orchard", "action": "create",
                "data": {"appId": f["id"], "growerId": f["subActorId"],  # growerId -> subActorExternalId: the owner, by YOUR id
                         "name": f["name"], "variety": f["variety"], "plantedOn": f["plantedOn"],
                         "latitude": f["latitude"], "longitude": f["longitude"],
                         "outline": f["outline"]}}  # 3+ outline points make a polygon
    return {"type": "site", "action": "create",
            "data": {"appId": f["id"], "name": f["name"], "description": f["description"],
                     "latitude": f["latitude"], "longitude": f["longitude"]}}


class Integration:
    def __init__(self, store, client):
        """``client`` is the SDK client made by config.connect(), or None when no OBENS_API_KEY is set (the platform
        then just runs on its own, nothing is sent)."""
        self.store = store
        self.client = client
        self.targets = {}  # account id -> the syncers of that account, built once
        self.lock = threading.Lock()

    # ----------------------------------------------------------------------------------------------------------
    # 2. One "target" per account: the client that acts as that account + the syncers that write its data.
    # ----------------------------------------------------------------------------------------------------------
    def target(self, account_id):
        with self.lock:
            if account_id in self.targets:
                return self.targets[account_id]
            account = self.store.account(account_id)  # {id, name: "Mango Cooperative", stage: "Fresh Mango"}

            # accounts.accessible() lists the key's own account + every account that delegated to it (config.py).
            # acting_as() returns a client for THAT account: client.for_account(id) adds "?account-id=" to every call,
            # so what you read and write is that account's own farmers, facilities and id table. The key's own
            # account needs no switch.
            _, client = acting_as(self.client, account["name"])

            # Each account only sees its own lists. We look the list up BY NAME ("Fresh Mango"), with the client of
            # that account, then give its id to the grower mapper: every sub-actor of this account is created inside it.
            element = find_element(client, account["stage"])

            target = {
                # client.syncer(kind, map=...) = "keep this kind of record in sync, using this mapper".
                #   "sub_actors" -> /integration/sub-actors  (farmers)
                #   "facilities" -> /integration/facilities  (orchards and sites)
                # A syncer offers init(list) (load a whole table, with a report), push(event) (one change) and remove(event).
                "sub_actors": client.syncer("sub_actors", map=grower_mapper(element["id"])),
                "orchards": client.syncer("facilities", map=orchard_mapper()),
                "sites": client.syncer("facilities", map=site_mapper()),
            }
            self.targets[account_id] = target
            return target

    def attempt(self, label, account_id, step):
        """Runs one sync step. A failure is logged and swallowed: saving in YOUR platform must never depend on Obens
        being reachable. The next start() load reconciles anything that was missed."""
        if self.client is None:
            return  # no key: nothing to do
        try:
            result = step(self.target(account_id))
            print(f"[sync] {label}: {result['outcome'] if isinstance(result, dict) else 'ok'}", flush=True)
        except (Exception, SystemExit) as e:  # SystemExit: config.py exits with a message for an unknown account or list
            print(f"[sync] {label} failed: {e}", flush=True)

    # ----------------------------------------------------------------------------------------------------------
    # 3. At boot: load everything once (initial load), then keep up with changes.
    # ----------------------------------------------------------------------------------------------------------
    def start(self):
        if self.client is None:
            return print("[sync] OBENS_API_KEY is not set: running without synchronisation", flush=True)
        for account in self.store.accounts():
            self.attempt(f"initial load of {account['name']}", account["id"], lambda t, a=account: self._load(a, t))

    def _load(self, account, t):
        subs = [sub_actor_event(s) for s in self.store.sub_actors(account["id"])]
        facilities = [facility_event(f) for f in self.store.facilities(account["id"])]

        def report(kind, syncer, events):
            if not events:
                return
            # init(list) sends the whole table in bulk batches (500 at a time) and returns a report:
            # total, created, updated, failed, failures [{externalId, error}].
            # Sending the same table again just reports "updated": ids are never duplicated.
            r = syncer.init(events)
            print(f"[sync] {account['name']} {kind}: {r.total} records, {r.created} created, {r.updated} updated, {r.failed} failed", flush=True)
            for f in r.failures:
                print(f"[sync]   failed {f['externalId']}: {f['error']}", flush=True)

        # ORDER MATTERS: an orchard belongs to a sub-actor, which must already exist on the platform, so sub-actors first.
        report("sub-actors", t["sub_actors"], subs)
        report("orchards", t["orchards"], [e for e in facilities if e["type"] == "orchard"])
        report("sites", t["sites"], [e for e in facilities if e["type"] == "site"])

    # ----------------------------------------------------------------------------------------------------------
    # 4. After every change the user makes in the dashboard.
    # ----------------------------------------------------------------------------------------------------------
    def push_sub_actor(self, account_id, s):
        """Create or update a sub-actor. push(event) maps it and upserts it by external id; the result has
        {externalId, id (the Obens id), outcome: CREATED | UPDATED, error}."""
        self.attempt(f"sub-actor {s['id']} ({account_id})", account_id, lambda t: t["sub_actors"].push(sub_actor_event(s)))

    def remove_sub_actor(self, account_id, s):
        """Delete a sub-actor. remove(event) is a SOFT delete: the sub-actor gets the status DELETED, the platform keeps it."""
        self.attempt(f"delete sub-actor {s['id']} ({account_id})", account_id, lambda t: t["sub_actors"].remove(sub_actor_event(s)))

    def push_facility(self, account_id, f):
        """Create or update a facility. The event type picks the syncer: "orchard" (has an owner) or "site" (account
        level). An orchard is sent with subActorExternalId = its owner, so it belongs to that sub-actor on the platform."""
        e = facility_event(f)
        self.attempt(f"{e['type']} {f['id']} ({account_id})", account_id,
                     lambda t: (t["orchards"] if e["type"] == "orchard" else t["sites"]).push(e))

    def remove_facility(self, account_id, f):
        """Delete a facility (soft: sent with deleted = true). For an orchard the owner is part of its identity, so
        the event keeps growerId."""
        e = facility_event(f)
        self.attempt(f"delete {e['type']} {f['id']} ({account_id})", account_id,
                     lambda t: (t["orchards"] if e["type"] == "orchard" else t["sites"]).remove(e))

package so.obens.demo;

import so.obens.sdk.ObensClient;
import so.obens.sdk.SyncReport;
import so.obens.sdk.Syncer;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * THE WHOLE OBENS INTEGRATION LIVES IN THIS FILE.
 *
 * <p>The platform (Db, App, public/) knows nothing about Obens. It only calls the four push/remove methods at the
 * bottom of this class after it has saved a change, plus {@link #start()} once at boot. Read the file top to bottom:
 * every SDK call is explained.
 *
 * <p>Vocabulary (see the README for more):
 * <ul>
 *   <li><b>account</b>: a company of the supply chain ("Mango Cooperative"). The API key belongs to ONE account (the
 *       importer); the others "delegated" their account to it, so the key can also act for them.</li>
 *   <li><b>element</b>: a list of the supply chain ("Fresh Mango"). A sub-actor is created INSIDE one list.</li>
 *   <li><b>sub-actor</b>: a person the account works with. Obens calls it a "farmer" (type FARMER).</li>
 *   <li><b>facility</b>: a place. Owned by a sub-actor (an "orchard") or by the account itself (a "site").</li>
 *   <li><b>external id</b>: YOUR id for a record ("grw-001"). Obens remembers it, so sending the same record twice
 *       UPDATES it.</li>
 * </ul>
 */
final class Integration {
    // ---------------------------------------------------------------------------------------------------------------
    // 1. Your rows -> events.
    //    The SDK's mappers read a small "event" (type, action, data). Building it from a database row is the only
    //    thing you write per record type; the mapper (Mapping.java) then turns the event into the Obens payload.
    // ---------------------------------------------------------------------------------------------------------------

    /** A sub-actor row. {@code appId} is YOUR id: the mapper sends it as the external id. */
    static Mapping.Event subActorEvent(Db.SubActor s) {
        var data = new HashMap<String, Object>();
        data.put("appId", s.id());
        data.put("firstName", s.firstName());
        data.put("lastName", s.lastName());
        data.put("phone", s.phone());
        data.put("gender", s.gender());
        data.put("village", s.village());
        return new Mapping.Event("grower", "create", data); // push() only needs the data: "create" and "update" are the same upsert
    }

    /** A facility row. With an owner it is an "orchard" (a facility OWNED by that sub-actor), without one a "site". */
    static Mapping.Event facilityEvent(Db.Facility f) {
        var data = new HashMap<String, Object>();
        data.put("appId", f.id());
        data.put("name", f.name());
        data.put("latitude", f.latitude());
        data.put("longitude", f.longitude());
        if (f.subActorId() == null) {
            data.put("description", f.description());
            return new Mapping.Event("site", "create", data);
        }
        data.put("growerId", f.subActorId()); // growerId -> subActorExternalId: the owner, by YOUR id
        data.put("variety", f.variety());
        data.put("plantedOn", f.plantedOn());
        data.put("outline", f.outline()); // 3+ outline points make a polygon
        return new Mapping.Event("orchard", "create", data);
    }

    /** The client that acts as one account + the syncers that write its data. */
    private record Target(Syncer<Mapping.Event> subActors, Syncer<Mapping.Event> orchards, Syncer<Mapping.Event> sites) {
    }

    private final Db db;
    private final ObensClient client; // made by Config.connect(), or null when no OBENS_API_KEY is set
    private final Map<String, Target> targets = new ConcurrentHashMap<>(); // account id -> its syncers, built once

    /** With {@code client == null} the platform just runs on its own: nothing is sent. */
    Integration(Db db, ObensClient client) {
        this.db = db;
        this.client = client;
    }

    static Integration fromEnv(Db db) {
        var key = System.getenv("OBENS_API_KEY");
        return new Integration(db, key == null || key.isBlank() ? null : Config.connect());
    }

    // ---------------------------------------------------------------------------------------------------------------
    // 2. One "target" per account: the client that acts as that account + the syncers that write its data.
    // ---------------------------------------------------------------------------------------------------------------
    private synchronized Target target(String accountId) {
        var cached = targets.get(accountId);
        if (cached != null) return cached;
        var account = db.account(accountId); // (id, name "Mango Cooperative", stage "Fresh Mango")

        // accounts().accessible() lists the key's own account + every account that delegated to it (Config.java).
        // actingAs() returns a client for THAT account: client.forAccount(id) adds "?account-id=" to every call, so
        // what you read and write is that account's own farmers, facilities and id table. The key's own account needs
        // no switch.
        var acting = Config.actingAs(client, account.name()).client();

        // Each account only sees its own lists. We look the list up BY NAME ("Fresh Mango"), with the client of that
        // account, then give its id to the grower mapper: every sub-actor of this account is created inside it.
        var element = Config.findElement(acting, account.stage());

        var target = new Target(
                // client.syncer(kind, mapper) = "keep this kind of record in sync, using this mapper".
                //   SUB_ACTORS -> /integration/sub-actors  (farmers)
                //   FACILITIES -> /integration/facilities  (orchards and sites)
                // A syncer offers init(list) (load a whole table, with a report), push(event) (one change) and remove(event).
                acting.syncer(Syncer.Kind.SUB_ACTORS, Mapping.growerMapper(element.id())),
                acting.syncer(Syncer.Kind.FACILITIES, Mapping.orchardMapper()),
                acting.syncer(Syncer.Kind.FACILITIES, Mapping.siteMapper()));
        targets.put(accountId, target);
        return target;
    }

    /**
     * Runs one sync step. A failure is logged and swallowed: saving in YOUR platform must never depend on Obens being
     * reachable. The next {@link #start()} load reconciles anything that was missed.
     */
    private void attempt(String label, String accountId, Function<Target, Object> step) {
        if (client == null) return; // no key: nothing to do
        try {
            var result = step.apply(target(accountId));
            System.out.println("[sync] " + label + ": " + (result instanceof so.obens.sdk.SyncResult r ? r.outcome() : "ok"));
        } catch (Exception e) {
            System.out.println("[sync] " + label + " failed: " + e.getMessage());
        }
    }

    // ---------------------------------------------------------------------------------------------------------------
    // 3. At boot: load everything once (initial load), then keep up with changes.
    // ---------------------------------------------------------------------------------------------------------------
    void start() {
        if (client == null) {
            System.out.println("[sync] OBENS_API_KEY is not set: running without synchronisation");
            return;
        }
        for (var account : db.accounts()) {
            attempt("initial load of " + account.name(), account.id(), t -> {
                var subs = db.subActors(account.id()).stream().map(Integration::subActorEvent).toList();
                var facilities = db.facilities(account.id()).stream().map(Integration::facilityEvent).toList();

                // ORDER MATTERS: an orchard belongs to a sub-actor, which must already exist on the platform, so
                // sub-actors first.
                report(account.name(), "sub-actors", t.subActors(), subs);
                report(account.name(), "orchards", t.orchards(), facilities.stream().filter(e -> e.type().equals("orchard")).toList());
                report(account.name(), "sites", t.sites(), facilities.stream().filter(e -> e.type().equals("site")).toList());
                return null;
            });
        }
    }

    private static void report(String account, String kind, Syncer<Mapping.Event> syncer, List<Mapping.Event> events) {
        if (events.isEmpty()) return;
        // init(list) sends the whole table in bulk batches (500 at a time) and returns a report:
        // total, created, updated, failed, failures (externalId, error).
        // Sending the same table again just reports "updated": ids are never duplicated.
        SyncReport r = syncer.init(events);
        System.out.println("[sync] " + account + " " + kind + ": " + r.total() + " records, " + r.created() + " created, "
                + r.updated() + " updated, " + r.failed() + " failed");
        r.failures().forEach(f -> System.out.println("[sync]   failed " + f.externalId() + ": " + f.error()));
    }

    // ---------------------------------------------------------------------------------------------------------------
    // 4. After every change the user makes in the dashboard.
    // ---------------------------------------------------------------------------------------------------------------

    /** Create or update a sub-actor. push(event) maps it and upserts it by external id; the result has
     *  (externalId, id = the Obens id, outcome CREATED | UPDATED, error). */
    void pushSubActor(String accountId, Db.SubActor s) {
        attempt("sub-actor " + s.id() + " (" + accountId + ")", accountId, t -> t.subActors().push(subActorEvent(s)));
    }

    /** Delete a sub-actor. remove(event) is a SOFT delete: the sub-actor gets the status DELETED, the platform keeps it. */
    void removeSubActor(String accountId, Db.SubActor s) {
        attempt("delete sub-actor " + s.id() + " (" + accountId + ")", accountId, t -> t.subActors().remove(subActorEvent(s)));
    }

    /** Create or update a facility. The event type picks the syncer: "orchard" (has an owner) or "site" (account
     *  level). An orchard is sent with subActorExternalId = its owner, so it belongs to that sub-actor on the platform. */
    void pushFacility(String accountId, Db.Facility f) {
        var e = facilityEvent(f);
        attempt(e.type() + " " + f.id() + " (" + accountId + ")", accountId,
                t -> (e.type().equals("orchard") ? t.orchards() : t.sites()).push(e));
    }

    /** Delete a facility (soft: sent with deleted = true). For an orchard the owner is part of its identity, so the
     *  event keeps growerId. */
    void removeFacility(String accountId, Db.Facility f) {
        var e = facilityEvent(f);
        attempt("delete " + e.type() + " " + f.id() + " (" + accountId + ")", accountId,
                t -> (e.type().equals("orchard") ? t.orchards() : t.sites()).remove(e));
    }
}

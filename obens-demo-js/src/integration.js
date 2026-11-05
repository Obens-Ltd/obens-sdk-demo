// THE WHOLE OBENS INTEGRATION LIVES IN THIS FILE.
//
// The platform (db.js, server.js, public/) knows nothing about Obens. It only calls the four functions at the bottom
// of this class after it has saved a change:  pushSubActor / removeSubActor / pushFacility / removeFacility,
// plus start() once at boot. Read the file top to bottom: every SDK call is explained.
//
// Vocabulary (see the README for more):
//   account   a company of the supply chain ("Mango Cooperative"). The API key belongs to ONE account (the importer);
//             the others "delegated" their account to it, so the key can also act for them.
//   element   a list of the supply chain ("Fresh Mango"). A sub-actor is created INSIDE one list.
//   sub-actor a person the account works with. Obens calls it a "farmer" (type FARMER).
//   facility  a place. Owned by a sub-actor (an "orchard") or by the account itself (a "site").
//   external id  YOUR id for a record ("grw-001"). Obens remembers it, so sending the same record twice UPDATES it.
import { actingAs, findElement } from './config.js';
import { growerMapper, orchardMapper, siteMapper } from './mapping.js';

// ---------------------------------------------------------------------------------------------------------------
// 1. Your rows -> events.
//    The SDK's mappers read a small "event" ({ type, action, data }). Building it from a database row is the only
//    thing you write per record type; the mapper (mapping.js) then turns the event into the Obens payload.
// ---------------------------------------------------------------------------------------------------------------

// A sub-actor row. `appId` is YOUR id: the mapper sends it as the external id.
export const subActorEvent = (s) => ({
  type: 'grower',
  action: 'create', // push() only needs the data; "create" and "update" are the same upsert
  data: { appId: s.id, firstName: s.firstName, lastName: s.lastName, phone: s.phone, gender: s.gender, village: s.village },
});

// A facility row. With an owner it is an "orchard" (a facility OWNED by that sub-actor), without one it is a "site".
export const facilityEvent = (f) =>
  f.subActorId
    ? {
        type: 'orchard',
        action: 'create',
        data: {
          appId: f.id, growerId: f.subActorId, // growerId -> subActorExternalId: the owner, by YOUR id
          name: f.name, variety: f.variety, plantedOn: f.plantedOn,
          latitude: f.latitude, longitude: f.longitude, outline: f.outline, // 3+ outline points make a polygon
        },
      }
    : {
        type: 'site',
        action: 'create',
        data: { appId: f.id, name: f.name, description: f.description, latitude: f.latitude, longitude: f.longitude },
      };

export class Integration {
  // `client` is the SDK client made by config.connect(), or null when no OBENS_API_KEY is set (the platform then just
  // runs on its own, nothing is sent).
  constructor(store, client) {
    this.store = store;
    this.client = client;
    this.targets = new Map(); // account id -> the syncers of that account, built once
  }

  // -------------------------------------------------------------------------------------------------------------
  // 2. One "target" per account: the client that acts as that account + the syncers that write its data.
  // -------------------------------------------------------------------------------------------------------------
  async target(accountId) {
    if (this.targets.has(accountId)) return this.targets.get(accountId);
    const account = this.store.account(accountId); // { id, name: "Mango Cooperative", stage: "Fresh Mango" }

    // accounts.accessible() lists the key's own account + every account that delegated to it (config.js).
    // actingAs() returns a client for THAT account: client.forAccount(id) adds "?account-id=" to every call, so what
    // you read and write is that account's own farmers, facilities and id table. The key's own account needs no switch.
    const { client } = await actingAs(this.client, account.name);

    // Each account only sees its own lists. We look the list up BY NAME ("Fresh Mango"), with the client of that
    // account, then give its id to the grower mapper: every sub-actor of this account is created inside that list.
    const element = await findElement(client, account.stage);

    const target = {
      // client.syncer(kind, { map }) = "keep this kind of record in sync, using this mapper".
      //   'subActors'  -> /integration/sub-actors  (farmers)
      //   'facilities' -> /integration/facilities  (orchards and sites)
      // A syncer offers init(list) (load a whole table, with a report), push(event) (one change) and remove(event).
      subActors: client.syncer('subActors', { map: growerMapper(element.id) }),
      orchards: client.syncer('facilities', { map: orchardMapper() }),
      sites: client.syncer('facilities', { map: siteMapper() }),
    };
    this.targets.set(accountId, target);
    return target;
  }

  // Runs one sync step. A failure is logged and swallowed: saving in YOUR platform must never depend on Obens being
  // reachable. The next start() load reconciles anything that was missed.
  async attempt(label, accountId, step) {
    if (!this.client) return; // no key: nothing to do
    try {
      const result = await step(await this.target(accountId));
      console.log(`[sync] ${label}: ${result?.outcome ?? 'ok'}`);
    } catch (e) {
      console.warn(`[sync] ${label} failed: ${e.message}`);
    }
  }

  // -------------------------------------------------------------------------------------------------------------
  // 3. At boot: load everything once (initial load), then keep up with changes.
  // -------------------------------------------------------------------------------------------------------------
  async start() {
    if (!this.client) return console.log('[sync] OBENS_API_KEY is not set: running without synchronisation');
    for (const { id, name } of this.store.accounts()) {
      await this.attempt(`initial load of ${name}`, id, async (t) => {
        const subs = this.store.subActors(id).map(subActorEvent);
        const facilities = this.store.facilities(id).map(facilityEvent);

        // init(list) sends the whole table in bulk batches (500 at a time) and returns a report:
        // { total, created, updated, failed, failures: [{ externalId, error }] }.
        // Sending the same table again just reports "updated": ids are never duplicated.
        // ORDER MATTERS: an orchard belongs to a sub-actor, which must already exist on the platform, so sub-actors first.
        const report = async (kind, syncer, events) => {
          if (!events.length) return;
          const r = await syncer.init(events);
          console.log(`[sync] ${name} ${kind}: ${r.total} records, ${r.created} created, ${r.updated} updated, ${r.failed} failed`);
          r.failures.forEach((f) => console.warn(`[sync]   failed ${f.externalId}: ${f.error}`));
        };
        await report('sub-actors', t.subActors, subs);
        await report('orchards', t.orchards, facilities.filter((e) => e.type === 'orchard'));
        await report('sites', t.sites, facilities.filter((e) => e.type === 'site'));
      });
    }
  }

  // -------------------------------------------------------------------------------------------------------------
  // 4. After every change the user makes in the dashboard.
  // -------------------------------------------------------------------------------------------------------------

  // Create or update a sub-actor. push(event) maps it and upserts it by external id; the returned result has
  // { externalId, id (the Obens id), outcome: 'CREATED' | 'UPDATED', error }.
  pushSubActor(accountId, s) {
    return this.attempt(`sub-actor ${s.id} (${accountId})`, accountId, (t) => t.subActors.push(subActorEvent(s)));
  }

  // Delete a sub-actor. remove(event) is a SOFT delete: the sub-actor gets the status DELETED, the platform keeps it.
  removeSubActor(accountId, s) {
    return this.attempt(`delete sub-actor ${s.id} (${accountId})`, accountId, (t) => t.subActors.remove(subActorEvent(s)));
  }

  // Create or update a facility. The event type picks the syncer: "orchard" (has an owner) or "site" (account level).
  // An orchard is sent with subActorExternalId = its owner, so it belongs to that sub-actor on the platform.
  pushFacility(accountId, f) {
    const event = facilityEvent(f);
    return this.attempt(`${event.type} ${f.id} (${accountId})`, accountId, (t) => (event.type === 'orchard' ? t.orchards : t.sites).push(event));
  }

  // Delete a facility (soft: sent with deleted = true). For an orchard the owner is part of its identity, so the
  // event keeps growerId.
  removeFacility(accountId, f) {
    const event = facilityEvent(f);
    return this.attempt(`delete ${event.type} ${f.id} (${accountId})`, accountId, (t) => (event.type === 'orchard' ? t.orchards : t.sites).remove(event));
  }
}

<?php

/**
 * THE WHOLE OBENS INTEGRATION LIVES IN THIS FILE.
 *
 * The platform (Db.php, public/index.php, public/index.html) knows nothing about Obens. It only calls the methods
 * at the bottom of this class after it has saved a change (pushSubActor / removeSubActor / pushFacility /
 * removeFacility), and bin/sync.php calls start() once at boot. Read the file top to bottom: every SDK call is
 * explained.
 *
 * Vocabulary (see the README for more):
 *   account      a company of the supply chain ("Mango Cooperative"). The API key belongs to ONE account (the importer);
 *                the others "delegated" their account to it, so the key can also act for them.
 *   element      a list of the supply chain ("Fresh Mango"). A sub-actor is created INSIDE one list.
 *   sub-actor    a person the account works with. Obens calls it a "farmer" (type FARMER).
 *   facility     a place. Owned by a sub-actor (an "orchard") or by the account itself (a "site").
 *   external id  YOUR id for a record ("grw-001"). Obens remembers it, so sending the same record twice UPDATES it.
 */

declare(strict_types=1);

namespace Demo;

use Obens\Sdk\ObensClient;

final class Integration
{
    /** account id => the syncers of that account (PHP forgets everything between requests, so this lives one request) */
    private array $targets = [];

    /** `$client` is the SDK client made by Config::connect(), or null when no OBENS_API_KEY is set (the platform then
     *  just runs on its own, nothing is sent). */
    public function __construct(private Db $db, private ?ObensClient $client)
    {
    }

    public static function fromEnv(Db $db): self
    {
        return new self($db, getenv('OBENS_API_KEY') ? Config::connect() : null);
    }

    // ------------------------------------------------------------------------------------------------------------
    // 1. Your rows -> events.
    //    The SDK's mappers read a small "event" (type, action, data). Building it from a database row is the only
    //    thing you write per record type; the mapper (Mapping.php) then turns the event into the Obens payload.
    // ------------------------------------------------------------------------------------------------------------

    /** A sub-actor row. `appId` is YOUR id: the mapper sends it as the external id. */
    public static function subActorEvent(array $s): array
    {
        return ['type' => 'grower', 'action' => 'create', // push() only needs the data: "create" and "update" are the same upsert
            'data' => ['appId' => $s['id'], 'firstName' => $s['firstName'], 'lastName' => $s['lastName'],
                'phone' => $s['phone'], 'gender' => $s['gender'], 'village' => $s['village']]];
    }

    /** A facility row. With an owner it is an "orchard" (a facility OWNED by that sub-actor), without one a "site". */
    public static function facilityEvent(array $f): array
    {
        if ($f['subActorId']) {
            return ['type' => 'orchard', 'action' => 'create',
                'data' => ['appId' => $f['id'], 'growerId' => $f['subActorId'], // growerId -> subActorExternalId: the owner, by YOUR id
                    'name' => $f['name'], 'variety' => $f['variety'], 'plantedOn' => $f['plantedOn'],
                    'latitude' => $f['latitude'], 'longitude' => $f['longitude'],
                    'outline' => $f['outline']]]; // 3+ outline points make a polygon
        }
        return ['type' => 'site', 'action' => 'create',
            'data' => ['appId' => $f['id'], 'name' => $f['name'], 'description' => $f['description'],
                'latitude' => $f['latitude'], 'longitude' => $f['longitude']]];
    }

    // ------------------------------------------------------------------------------------------------------------
    // 2. One "target" per account: the client that acts as that account + the syncers that write its data.
    // ------------------------------------------------------------------------------------------------------------

    private function target(string $accountId): array
    {
        if (isset($this->targets[$accountId])) {
            return $this->targets[$accountId];
        }
        $account = $this->db->account($accountId); // ['id', 'name' => 'Mango Cooperative', 'stage' => 'Fresh Mango']

        // accounts->accessible() lists the key's own account + every account that delegated to it (Config.php).
        // actingAs() returns a client for THAT account: $client->forAccount($id) adds "?account-id=" to every call,
        // so what you read and write is that account's own farmers, facilities and id table. The key's own account
        // needs no switch.
        [, $client] = Config::actingAs($this->client, $account['name']);

        // Each account only sees its own lists. We look the list up BY NAME ("Fresh Mango"), with the client of that
        // account, then give its id to the grower mapper: every sub-actor of this account is created inside it.
        $element = Config::findElement($client, $account['stage']);

        return $this->targets[$accountId] = [
            // $client->syncer($kind, $mapper) = "keep this kind of record in sync, using this mapper".
            //   'sub_actors' -> /integration/sub-actors  (farmers)
            //   'facilities' -> /integration/facilities  (orchards and sites)
            // A syncer offers init($list) (load a whole table, with a report), push($event) (one change) and remove($event).
            'sub_actors' => $client->syncer('sub_actors', Mapping::growerMapper($element['id'])),
            'orchards' => $client->syncer('facilities', Mapping::orchardMapper()),
            'sites' => $client->syncer('facilities', Mapping::siteMapper()),
        ];
    }

    /**
     * Runs one sync step. A failure is logged and swallowed: saving in YOUR platform must never depend on Obens being
     * reachable. The next start() load reconciles anything that was missed.
     */
    private function attempt(string $label, string $accountId, callable $step): void
    {
        if ($this->client === null) {
            return; // no key: nothing to do
        }
        try {
            $result = $step($this->target($accountId));
            self::log("$label: " . (is_array($result) ? $result['outcome'] : 'ok'));
        } catch (\Throwable $e) {
            self::log("$label failed: " . $e->getMessage());
        }
    }

    private static function log(string $message): void
    {
        file_put_contents('php://stderr', "[sync] $message\n");
    }

    // ------------------------------------------------------------------------------------------------------------
    // 3. At boot (bin/sync.php): load everything once (initial load), then keep up with changes.
    // ------------------------------------------------------------------------------------------------------------

    public function start(): void
    {
        if ($this->client === null) {
            self::log('OBENS_API_KEY is not set: running without synchronisation');
            return;
        }
        foreach ($this->db->accounts() as $account) {
            $this->attempt("initial load of {$account['name']}", $account['id'], function (array $t) use ($account) {
                $subs = array_map([self::class, 'subActorEvent'], $this->db->subActors($account['id']));
                $facilities = array_map([self::class, 'facilityEvent'], $this->db->facilities($account['id']));
                $of = fn (string $type) => array_values(array_filter($facilities, fn ($e) => $e['type'] === $type));

                // init($list) sends the whole table in bulk batches (500 at a time) and returns a report:
                // total, created, updated, failed, failures [['externalId', 'error']].
                // Sending the same table again just reports "updated": ids are never duplicated.
                $report = function (string $kind, $syncer, array $events) use ($account) {
                    if (!$events) {
                        return;
                    }
                    $r = $syncer->init($events);
                    self::log("{$account['name']} $kind: $r->total records, $r->created created, $r->updated updated, $r->failed failed");
                    foreach ($r->failures as $f) {
                        self::log("  failed {$f['externalId']}: {$f['error']}");
                    }
                };
                // ORDER MATTERS: an orchard belongs to a sub-actor, which must already exist on the platform, so sub-actors first.
                $report('sub-actors', $t['sub_actors'], $subs);
                $report('orchards', $t['orchards'], $of('orchard'));
                $report('sites', $t['sites'], $of('site'));
            });
        }
    }

    // ------------------------------------------------------------------------------------------------------------
    // 4. After every change the user makes in the dashboard.
    // ------------------------------------------------------------------------------------------------------------

    /** Create or update a sub-actor. push($event) maps it and upserts it by external id; the result has
     *  ['externalId', 'id' (the Obens id), 'outcome' => CREATED | UPDATED, 'error']. */
    public function pushSubActor(string $accountId, array $s): void
    {
        $this->attempt("sub-actor {$s['id']} ($accountId)", $accountId, fn ($t) => $t['sub_actors']->push(self::subActorEvent($s)));
    }

    /** Delete a sub-actor. remove($event) is a SOFT delete: the sub-actor gets the status DELETED, the platform keeps it. */
    public function removeSubActor(string $accountId, array $s): void
    {
        $this->attempt("delete sub-actor {$s['id']} ($accountId)", $accountId, fn ($t) => $t['sub_actors']->remove(self::subActorEvent($s)));
    }

    /** Create or update a facility. The event type picks the syncer: "orchard" (has an owner) or "site" (account
     *  level). An orchard is sent with subActorExternalId = its owner, so it belongs to that sub-actor on the platform. */
    public function pushFacility(string $accountId, array $f): void
    {
        $e = self::facilityEvent($f);
        $this->attempt("{$e['type']} {$f['id']} ($accountId)", $accountId,
            fn ($t) => $t[$e['type'] === 'orchard' ? 'orchards' : 'sites']->push($e));
    }

    /** Delete a facility (soft: sent with deleted = true). For an orchard the owner is part of its identity, so the
     *  event keeps growerId. */
    public function removeFacility(string $accountId, array $f): void
    {
        $e = self::facilityEvent($f);
        $this->attempt("delete {$e['type']} {$f['id']} ($accountId)", $accountId,
            fn ($t) => $t[$e['type'] === 'orchard' ? 'orchards' : 'sites']->remove($e));
    }
}

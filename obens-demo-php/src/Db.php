<?php

declare(strict_types=1);

namespace Demo;

/**
 * The demo platform's own database: plain SQLite, nothing about Obens in here.
 *   accounts      the companies using the platform
 *   sub_actors    people an account works with (growers, slicers, packers...)
 *   facilities    places: owned by a sub-actor (sub_actor_id set) or by the account itself (NULL)
 */
final class Db
{
    private \PDO $pdo;

    public function __construct(string $path, string $seedFile)
    {
        $this->pdo = new \PDO('sqlite:' . $path);
        $this->pdo->setAttribute(\PDO::ATTR_ERRMODE, \PDO::ERRMODE_EXCEPTION);
        $this->pdo->setAttribute(\PDO::ATTR_DEFAULT_FETCH_MODE, \PDO::FETCH_ASSOC);
        $this->pdo->exec('PRAGMA busy_timeout = 5000'); // the sync script and the web server share the file
        $this->pdo->exec(<<<'SQL'
            CREATE TABLE IF NOT EXISTS accounts (id TEXT PRIMARY KEY, name TEXT NOT NULL, stage TEXT NOT NULL);
            CREATE TABLE IF NOT EXISTS sub_actors (
              account_id TEXT NOT NULL REFERENCES accounts(id), id TEXT NOT NULL,
              first_name TEXT NOT NULL, last_name TEXT NOT NULL, phone TEXT, gender TEXT, village TEXT,
              PRIMARY KEY (account_id, id));
            CREATE TABLE IF NOT EXISTS facilities (
              account_id TEXT NOT NULL REFERENCES accounts(id), id TEXT NOT NULL, sub_actor_id TEXT,
              name TEXT NOT NULL, description TEXT, variety TEXT, planted_on TEXT, latitude REAL, longitude REAL, outline TEXT,
              PRIMARY KEY (account_id, id));
            SQL);
        if ((int) $this->q('SELECT COUNT(*) AS n FROM accounts')[0]['n'] === 0) {
            $this->seed($seedFile);
        }
    }

    private function q(string $sql, array $args = []): array
    {
        $st = $this->pdo->prepare($sql);
        $st->execute($args);
        return $st->fetchAll();
    }

    private function run(string $sql, array $args): void
    {
        $this->pdo->prepare($sql)->execute($args);
    }

    /** First start only: fill the tables from data/seed.json. */
    private function seed(string $file): void
    {
        $this->pdo->beginTransaction();
        foreach (json_decode((string) file_get_contents($file), true)['accounts'] as $a) {
            $this->run('INSERT INTO accounts (id, name, stage) VALUES (?, ?, ?)', [$a['id'], $a['name'], $a['stage']]);
            foreach ($a['subActors'] as $s) {
                $this->saveSubActor($a['id'], $s);
            }
            foreach ($a['facilities'] as $f) {
                $this->saveFacility($a['id'], $f);
            }
        }
        $this->pdo->commit();
    }

    private static function subActor(array $r): array
    {
        return ['id' => $r['id'], 'firstName' => $r['first_name'], 'lastName' => $r['last_name'],
            'phone' => $r['phone'], 'gender' => $r['gender'], 'village' => $r['village']];
    }

    private static function facility(array $r): array
    {
        return ['id' => $r['id'], 'subActorId' => $r['sub_actor_id'], 'name' => $r['name'], 'description' => $r['description'],
            'variety' => $r['variety'], 'plantedOn' => $r['planted_on'],
            'latitude' => $r['latitude'] === null ? null : (float) $r['latitude'],
            'longitude' => $r['longitude'] === null ? null : (float) $r['longitude'],
            'outline' => $r['outline'] ? json_decode($r['outline'], true) : null];
    }

    public function accounts(): array
    {
        return array_map(fn ($r) => ['id' => $r['id'], 'name' => $r['name'], 'stage' => $r['stage'],
            'subActors' => (int) $r['subs'], 'facilities' => (int) $r['facs']], $this->q(
            'SELECT a.*, (SELECT COUNT(*) FROM sub_actors WHERE account_id = a.id) AS subs,
             (SELECT COUNT(*) FROM facilities WHERE account_id = a.id) AS facs FROM accounts a ORDER BY a.rowid'));
    }

    public function account(string $id): ?array
    {
        return $this->q('SELECT * FROM accounts WHERE id = ?', [$id])[0] ?? null;
    }

    public function subActors(string $account): array
    {
        return array_map([self::class, 'subActor'], $this->q('SELECT * FROM sub_actors WHERE account_id = ? ORDER BY id', [$account]));
    }

    public function subActorById(string $account, string $id): ?array
    {
        $r = $this->q('SELECT * FROM sub_actors WHERE account_id = ? AND id = ?', [$account, $id])[0] ?? null;
        return $r ? self::subActor($r) : null;
    }

    public function saveSubActor(string $account, array $s): void
    {
        $this->run('INSERT INTO sub_actors (account_id, id, first_name, last_name, phone, gender, village) VALUES (?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (account_id, id) DO UPDATE SET first_name = excluded.first_name, last_name = excluded.last_name,
            phone = excluded.phone, gender = excluded.gender, village = excluded.village',
            [$account, $s['id'], $s['firstName'], $s['lastName'], $s['phone'] ?? null, $s['gender'] ?? null, $s['village'] ?? null]);
    }

    public function deleteSubActor(string $account, string $id): void
    {
        $this->run('DELETE FROM sub_actors WHERE account_id = ? AND id = ?', [$account, $id]);
    }

    public function facilities(string $account): array
    {
        return array_map([self::class, 'facility'], $this->q('SELECT * FROM facilities WHERE account_id = ? ORDER BY id', [$account]));
    }

    public function facilityById(string $account, string $id): ?array
    {
        $r = $this->q('SELECT * FROM facilities WHERE account_id = ? AND id = ?', [$account, $id])[0] ?? null;
        return $r ? self::facility($r) : null;
    }

    public function saveFacility(string $account, array $f): void
    {
        $this->run('INSERT INTO facilities (account_id, id, sub_actor_id, name, description, variety, planted_on, latitude, longitude, outline)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (account_id, id) DO UPDATE SET name = excluded.name, description = excluded.description,
            variety = excluded.variety, planted_on = excluded.planted_on, latitude = excluded.latitude,
            longitude = excluded.longitude, outline = excluded.outline',
            [$account, $f['id'], $f['subActorId'] ?? null, $f['name'], $f['description'] ?? null, $f['variety'] ?? null,
                $f['plantedOn'] ?? null, $f['latitude'] ?? null, $f['longitude'] ?? null,
                !empty($f['outline']) ? json_encode($f['outline']) : null]);
    }

    public function deleteFacility(string $account, string $id): void
    {
        $this->run('DELETE FROM facilities WHERE account_id = ? AND id = ?', [$account, $id]);
    }

    /** Next free id of an account: grw-004, orc-005, site-002... */
    public function nextId(string $account, string $table, string $prefix): string
    {
        $max = 0;
        foreach ($this->q("SELECT id FROM $table WHERE account_id = ? AND id LIKE ?", [$account, "$prefix-%"]) as $r) {
            $n = substr($r['id'], strlen($prefix) + 1);
            if (ctype_digit($n)) {
                $max = max($max, (int) $n);
            }
        }
        return sprintf('%s-%03d', $prefix, $max + 1);
    }
}

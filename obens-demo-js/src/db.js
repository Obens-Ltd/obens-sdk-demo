// The demo platform's own database: plain SQLite, nothing about Obens in here.
//   accounts      the companies using the platform
//   sub_actors    people an account works with (growers, slicers, packers...)
//   facilities    places: owned by a sub-actor (sub_actor_id set) or by the account itself (null)
import { readFileSync } from 'node:fs';
import { DatabaseSync } from 'node:sqlite';

export function openDb(path, seedFile) {
  const db = new DatabaseSync(path);
  db.exec(`
    CREATE TABLE IF NOT EXISTS accounts (id TEXT PRIMARY KEY, name TEXT NOT NULL, stage TEXT NOT NULL);
    CREATE TABLE IF NOT EXISTS sub_actors (
      account_id TEXT NOT NULL REFERENCES accounts(id), id TEXT NOT NULL,
      first_name TEXT NOT NULL, last_name TEXT NOT NULL, phone TEXT, gender TEXT, village TEXT,
      PRIMARY KEY (account_id, id));
    CREATE TABLE IF NOT EXISTS facilities (
      account_id TEXT NOT NULL REFERENCES accounts(id), id TEXT NOT NULL, sub_actor_id TEXT,
      name TEXT NOT NULL, description TEXT, variety TEXT, planted_on TEXT, latitude REAL, longitude REAL, outline TEXT,
      PRIMARY KEY (account_id, id));
  `);
  if (db.prepare('SELECT COUNT(*) AS n FROM accounts').get().n === 0) seed(db, seedFile);
  return new Store(db);
}

// First start only: fill the tables from data/seed.json.
function seed(db, seedFile) {
  const { accounts } = JSON.parse(readFileSync(seedFile));
  const store = new Store(db);
  for (const a of accounts) {
    db.prepare('INSERT INTO accounts (id, name, stage) VALUES (?, ?, ?)').run(a.id, a.name, a.stage);
    for (const s of a.subActors) store.saveSubActor(a.id, s);
    for (const f of a.facilities) store.saveFacility(a.id, f);
  }
}

const subActor = (r) => r && { id: r.id, firstName: r.first_name, lastName: r.last_name, phone: r.phone, gender: r.gender, village: r.village };
const facility = (r) => r && {
  id: r.id, subActorId: r.sub_actor_id, name: r.name, description: r.description, variety: r.variety,
  plantedOn: r.planted_on, latitude: r.latitude, longitude: r.longitude, outline: r.outline ? JSON.parse(r.outline) : null,
};

export class Store {
  constructor(db) { this.db = db; }
  q(sql, ...args) { return this.db.prepare(sql).all(...args); }

  accounts() {
    return this.q(`SELECT a.*,
      (SELECT COUNT(*) FROM sub_actors WHERE account_id = a.id) AS subs,
      (SELECT COUNT(*) FROM facilities WHERE account_id = a.id) AS facs FROM accounts a ORDER BY a.rowid`)
      .map((r) => ({ id: r.id, name: r.name, stage: r.stage, subActors: r.subs, facilities: r.facs }));
  }
  account(id) { return this.q('SELECT * FROM accounts WHERE id = ?', id)[0]; }

  subActors(account) { return this.q('SELECT * FROM sub_actors WHERE account_id = ? ORDER BY id', account).map(subActor); }
  subActor(account, id) { return subActor(this.q('SELECT * FROM sub_actors WHERE account_id = ? AND id = ?', account, id)[0]); }
  saveSubActor(account, s) {
    this.db.prepare(`INSERT INTO sub_actors (account_id, id, first_name, last_name, phone, gender, village) VALUES (?, ?, ?, ?, ?, ?, ?)
      ON CONFLICT (account_id, id) DO UPDATE SET first_name = excluded.first_name, last_name = excluded.last_name,
      phone = excluded.phone, gender = excluded.gender, village = excluded.village`)
      .run(account, s.id, s.firstName, s.lastName, s.phone ?? null, s.gender ?? null, s.village ?? null);
  }
  deleteSubActor(account, id) { this.db.prepare('DELETE FROM sub_actors WHERE account_id = ? AND id = ?').run(account, id); }

  facilities(account) { return this.q('SELECT * FROM facilities WHERE account_id = ? ORDER BY id', account).map(facility); }
  facility(account, id) { return facility(this.q('SELECT * FROM facilities WHERE account_id = ? AND id = ?', account, id)[0]); }
  saveFacility(account, f) {
    this.db.prepare(`INSERT INTO facilities (account_id, id, sub_actor_id, name, description, variety, planted_on, latitude, longitude, outline)
      VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
      ON CONFLICT (account_id, id) DO UPDATE SET name = excluded.name, description = excluded.description, variety = excluded.variety,
      planted_on = excluded.planted_on, latitude = excluded.latitude, longitude = excluded.longitude, outline = excluded.outline`)
      .run(account, f.id, f.subActorId ?? null, f.name, f.description ?? null, f.variety ?? null, f.plantedOn ?? null,
        f.latitude ?? null, f.longitude ?? null, f.outline?.length ? JSON.stringify(f.outline) : null);
  }
  deleteFacility(account, id) { this.db.prepare('DELETE FROM facilities WHERE account_id = ? AND id = ?').run(account, id); }

  // Next free id of an account: grw-004, orc-005, site-002...
  nextId(account, table, prefix) {
    const rows = this.q(`SELECT id FROM ${table} WHERE account_id = ? AND id LIKE ?`, account, `${prefix}-%`);
    const max = Math.max(0, ...rows.map((r) => Number(r.id.slice(prefix.length + 1)) || 0));
    return `${prefix}-${String(max + 1).padStart(3, '0')}`;
  }
}

"""The demo platform's own database: plain SQLite, nothing about Obens in here.

  accounts      the companies using the platform
  sub_actors    people an account works with (growers, slicers, packers...)
  facilities    places: owned by a sub-actor (sub_actor_id set) or by the account itself (NULL)
"""
import json
import sqlite3
import threading
from pathlib import Path

SCHEMA = """
CREATE TABLE IF NOT EXISTS accounts (id TEXT PRIMARY KEY, name TEXT NOT NULL, stage TEXT NOT NULL);
CREATE TABLE IF NOT EXISTS sub_actors (
  account_id TEXT NOT NULL REFERENCES accounts(id), id TEXT NOT NULL,
  first_name TEXT NOT NULL, last_name TEXT NOT NULL, phone TEXT, gender TEXT, village TEXT,
  PRIMARY KEY (account_id, id));
CREATE TABLE IF NOT EXISTS facilities (
  account_id TEXT NOT NULL REFERENCES accounts(id), id TEXT NOT NULL, sub_actor_id TEXT,
  name TEXT NOT NULL, description TEXT, variety TEXT, planted_on TEXT, latitude REAL, longitude REAL, outline TEXT,
  PRIMARY KEY (account_id, id));
"""


def _sub_actor(r):
    return r and {"id": r["id"], "firstName": r["first_name"], "lastName": r["last_name"],
                  "phone": r["phone"], "gender": r["gender"], "village": r["village"]}


def _facility(r):
    return r and {"id": r["id"], "subActorId": r["sub_actor_id"], "name": r["name"], "description": r["description"],
                  "variety": r["variety"], "plantedOn": r["planted_on"], "latitude": r["latitude"],
                  "longitude": r["longitude"], "outline": json.loads(r["outline"]) if r["outline"] else None}


class Store:
    def __init__(self, path, seed_file):
        # one connection shared by the request threads and the sync thread: the lock keeps them apart
        self.db = sqlite3.connect(path, check_same_thread=False)
        self.db.row_factory = sqlite3.Row
        self.lock = threading.RLock()
        self.db.executescript(SCHEMA)
        if self.q("SELECT COUNT(*) AS n FROM accounts")[0]["n"] == 0:
            self._seed(seed_file)

    def q(self, sql, *args):
        with self.lock:
            return self.db.execute(sql, args).fetchall()

    def run(self, sql, *args):
        with self.lock:
            self.db.execute(sql, args)
            self.db.commit()

    def _seed(self, seed_file):
        """First start only: fill the tables from data/seed.json."""
        for a in json.loads(Path(seed_file).read_text(encoding="utf-8"))["accounts"]:
            self.run("INSERT INTO accounts (id, name, stage) VALUES (?, ?, ?)", a["id"], a["name"], a["stage"])
            for s in a["subActors"]:
                self.save_sub_actor(a["id"], s)
            for f in a["facilities"]:
                self.save_facility(a["id"], f)

    def accounts(self):
        rows = self.q("""SELECT a.*, (SELECT COUNT(*) FROM sub_actors WHERE account_id = a.id) AS subs,
                         (SELECT COUNT(*) FROM facilities WHERE account_id = a.id) AS facs FROM accounts a ORDER BY a.rowid""")
        return [{"id": r["id"], "name": r["name"], "stage": r["stage"], "subActors": r["subs"], "facilities": r["facs"]} for r in rows]

    def account(self, account_id):
        rows = self.q("SELECT * FROM accounts WHERE id = ?", account_id)
        return dict(rows[0]) if rows else None

    def sub_actors(self, account):
        return [_sub_actor(r) for r in self.q("SELECT * FROM sub_actors WHERE account_id = ? ORDER BY id", account)]

    def sub_actor(self, account, sub_id):
        rows = self.q("SELECT * FROM sub_actors WHERE account_id = ? AND id = ?", account, sub_id)
        return _sub_actor(rows[0]) if rows else None

    def save_sub_actor(self, account, s):
        self.run("""INSERT INTO sub_actors (account_id, id, first_name, last_name, phone, gender, village) VALUES (?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT (account_id, id) DO UPDATE SET first_name = excluded.first_name, last_name = excluded.last_name,
                    phone = excluded.phone, gender = excluded.gender, village = excluded.village""",
                 account, s["id"], s["firstName"], s["lastName"], s.get("phone"), s.get("gender"), s.get("village"))

    def delete_sub_actor(self, account, sub_id):
        self.run("DELETE FROM sub_actors WHERE account_id = ? AND id = ?", account, sub_id)

    def facilities(self, account):
        return [_facility(r) for r in self.q("SELECT * FROM facilities WHERE account_id = ? ORDER BY id", account)]

    def facility(self, account, fac_id):
        rows = self.q("SELECT * FROM facilities WHERE account_id = ? AND id = ?", account, fac_id)
        return _facility(rows[0]) if rows else None

    def save_facility(self, account, f):
        outline = json.dumps(f["outline"]) if f.get("outline") else None
        self.run("""INSERT INTO facilities (account_id, id, sub_actor_id, name, description, variety, planted_on, latitude, longitude, outline)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT (account_id, id) DO UPDATE SET name = excluded.name, description = excluded.description,
                    variety = excluded.variety, planted_on = excluded.planted_on, latitude = excluded.latitude,
                    longitude = excluded.longitude, outline = excluded.outline""",
                 account, f["id"], f.get("subActorId"), f["name"], f.get("description"), f.get("variety"),
                 f.get("plantedOn"), f.get("latitude"), f.get("longitude"), outline)

    def delete_facility(self, account, fac_id):
        self.run("DELETE FROM facilities WHERE account_id = ? AND id = ?", account, fac_id)

    def next_id(self, account, table, prefix):
        """Next free id of an account: grw-004, orc-005, site-002..."""
        rows = self.q(f"SELECT id FROM {table} WHERE account_id = ? AND id LIKE ?", account, f"{prefix}-%")
        numbers = [int(r["id"][len(prefix) + 1:]) for r in rows if r["id"][len(prefix) + 1:].isdigit()]
        return f"{prefix}-{max(numbers, default=0) + 1:03d}"

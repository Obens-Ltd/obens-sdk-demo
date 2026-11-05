package so.obens.demo;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * The demo platform's own database: plain SQLite, nothing about Obens in here.
 * <ul>
 *   <li>accounts: the companies using the platform</li>
 *   <li>sub_actors: people an account works with (growers, slicers, packers...)</li>
 *   <li>facilities: places, owned by a sub-actor (sub_actor_id set) or by the account itself (NULL)</li>
 * </ul>
 */
final class Db {
    record Account(String id, String name, String stage) {
    }

    record AccountSummary(String id, String name, String stage, int subActors, int facilities) {
    }

    record SubActor(String id, String firstName, String lastName, String phone, String gender, String village) {
    }

    record Facility(String id, String subActorId, String name, String description, String variety, String plantedOn,
                    Double latitude, Double longitude, List<Map<String, Object>> outline) {
    }

    private static final ObjectMapper JSON = new ObjectMapper();
    private final Connection db;

    Db(String path, Path seedFile) throws Exception {
        db = DriverManager.getConnection("jdbc:sqlite:" + path);
        try (var st = db.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS accounts (id TEXT PRIMARY KEY, name TEXT NOT NULL, stage TEXT NOT NULL)");
            st.execute("""
                    CREATE TABLE IF NOT EXISTS sub_actors (
                      account_id TEXT NOT NULL REFERENCES accounts(id), id TEXT NOT NULL,
                      first_name TEXT NOT NULL, last_name TEXT NOT NULL, phone TEXT, gender TEXT, village TEXT,
                      PRIMARY KEY (account_id, id))""");
            st.execute("""
                    CREATE TABLE IF NOT EXISTS facilities (
                      account_id TEXT NOT NULL REFERENCES accounts(id), id TEXT NOT NULL, sub_actor_id TEXT,
                      name TEXT NOT NULL, description TEXT, variety TEXT, planted_on TEXT, latitude REAL, longitude REAL, outline TEXT,
                      PRIMARY KEY (account_id, id))""");
        }
        if (query("SELECT COUNT(*) FROM accounts", rs -> rs.getInt(1)).get(0) == 0) seed(seedFile);
    }

    private interface Row<T> {
        T map(ResultSet rs) throws SQLException;
    }

    // one connection shared by the request threads and the sync thread: every method is synchronized
    private synchronized <T> List<T> query(String sql, Row<T> row, Object... args) {
        try (var st = db.prepareStatement(sql)) {
            bind(st, args);
            var out = new ArrayList<T>();
            try (var rs = st.executeQuery()) {
                while (rs.next()) out.add(row.map(rs));
            }
            return out;
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private synchronized void run(String sql, Object... args) {
        try (var st = db.prepareStatement(sql)) {
            bind(st, args);
            st.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void bind(PreparedStatement st, Object[] args) throws SQLException {
        for (int i = 0; i < args.length; i++) st.setObject(i + 1, args[i]);
    }

    /** First start only: fill the tables from data/seed.json. */
    @SuppressWarnings("unchecked")
    private void seed(Path file) throws Exception {
        Map<String, List<Map<String, Object>>> seed = JSON.readValue(file.toFile(), new TypeReference<>() {
        });
        for (var a : seed.get("accounts")) {
            run("INSERT INTO accounts (id, name, stage) VALUES (?, ?, ?)", a.get("id"), a.get("name"), a.get("stage"));
            var id = (String) a.get("id");
            for (var s : (List<Map<String, Object>>) a.get("subActors"))
                saveSubActor(id, JSON.convertValue(s, SubActor.class));
            for (var f : (List<Map<String, Object>>) a.get("facilities"))
                saveFacility(id, JSON.convertValue(f, Facility.class));
        }
    }

    List<AccountSummary> accounts() {
        return query("""
                SELECT a.*, (SELECT COUNT(*) FROM sub_actors WHERE account_id = a.id) AS subs,
                (SELECT COUNT(*) FROM facilities WHERE account_id = a.id) AS facs FROM accounts a ORDER BY a.rowid""",
                rs -> new AccountSummary(rs.getString("id"), rs.getString("name"), rs.getString("stage"), rs.getInt("subs"), rs.getInt("facs")));
    }

    Account account(String id) {
        return query("SELECT * FROM accounts WHERE id = ?", rs -> new Account(rs.getString("id"), rs.getString("name"), rs.getString("stage")), id)
                .stream().findFirst().orElse(null);
    }

    private static final Row<SubActor> SUB_ACTOR = rs -> new SubActor(rs.getString("id"), rs.getString("first_name"),
            rs.getString("last_name"), rs.getString("phone"), rs.getString("gender"), rs.getString("village"));

    List<SubActor> subActors(String account) {
        return query("SELECT * FROM sub_actors WHERE account_id = ? ORDER BY id", SUB_ACTOR, account);
    }

    SubActor subActor(String account, String id) {
        return query("SELECT * FROM sub_actors WHERE account_id = ? AND id = ?", SUB_ACTOR, account, id).stream().findFirst().orElse(null);
    }

    void saveSubActor(String account, SubActor s) {
        run("""
                INSERT INTO sub_actors (account_id, id, first_name, last_name, phone, gender, village) VALUES (?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (account_id, id) DO UPDATE SET first_name = excluded.first_name, last_name = excluded.last_name,
                phone = excluded.phone, gender = excluded.gender, village = excluded.village""",
                account, s.id(), s.firstName(), s.lastName(), s.phone(), s.gender(), s.village());
    }

    void deleteSubActor(String account, String id) {
        run("DELETE FROM sub_actors WHERE account_id = ? AND id = ?", account, id);
    }

    private static final Function<String, List<Map<String, Object>>> OUTLINE = text -> {
        try {
            return text == null ? null : JSON.readValue(text, new TypeReference<>() {
            });
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    };

    private static final Row<Facility> FACILITY = rs -> new Facility(rs.getString("id"), rs.getString("sub_actor_id"), rs.getString("name"),
            rs.getString("description"), rs.getString("variety"), rs.getString("planted_on"),
            (Double) rs.getObject("latitude"), (Double) rs.getObject("longitude"), OUTLINE.apply(rs.getString("outline")));

    List<Facility> facilities(String account) {
        return query("SELECT * FROM facilities WHERE account_id = ? ORDER BY id", FACILITY, account);
    }

    Facility facility(String account, String id) {
        return query("SELECT * FROM facilities WHERE account_id = ? AND id = ?", FACILITY, account, id).stream().findFirst().orElse(null);
    }

    void saveFacility(String account, Facility f) {
        String outline;
        try {
            outline = f.outline() == null || f.outline().isEmpty() ? null : JSON.writeValueAsString(f.outline());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        run("""
                INSERT INTO facilities (account_id, id, sub_actor_id, name, description, variety, planted_on, latitude, longitude, outline)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (account_id, id) DO UPDATE SET name = excluded.name, description = excluded.description,
                variety = excluded.variety, planted_on = excluded.planted_on, latitude = excluded.latitude,
                longitude = excluded.longitude, outline = excluded.outline""",
                account, f.id(), f.subActorId(), f.name(), f.description(), f.variety(), f.plantedOn(), f.latitude(), f.longitude(), outline);
    }

    void deleteFacility(String account, String id) {
        run("DELETE FROM facilities WHERE account_id = ? AND id = ?", account, id);
    }

    /** Next free id of an account: grw-004, orc-005, site-002... */
    String nextId(String account, String table, String prefix) {
        var ids = query("SELECT id FROM " + table + " WHERE account_id = ? AND id LIKE ?", rs -> rs.getString(1), account, prefix + "-%");
        int max = 0;
        for (var id : ids) {
            var n = id.substring(prefix.length() + 1);
            if (n.chars().allMatch(Character::isDigit) && !n.isEmpty()) max = Math.max(max, Integer.parseInt(n));
        }
        return String.format("%s-%03d", prefix, max + 1);
    }
}

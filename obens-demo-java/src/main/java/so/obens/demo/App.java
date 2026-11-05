package so.obens.demo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

/**
 * The demo platform: a small REST API + the dashboard (public/index.html). JDK only, no framework.
 * {@code OBENS_API_KEY=obk_... mvn -q compile exec:java}, then open http://localhost:3000
 */
public final class App {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern SUB_ACTORS = Pattern.compile("/api/accounts/([^/]+)/sub-actors(?:/([^/]+))?");
    private static final Pattern FACILITIES = Pattern.compile("/api/accounts/([^/]+)/facilities(?:/([^/]+))?");

    private static Db db;
    private static Integration integration;

    private static final class HttpError extends RuntimeException {
        final int status;

        HttpError(int status, String message) {
            super(message);
            this.status = status;
        }
    }

    // ---- validation ------------------------------------------------------------------------------------------------

    private static String required(JsonNode body, String field, String label) {
        var v = text(body, field);
        if (v == null) throw new HttpError(400, label + " is required");
        return v;
    }

    private static String text(JsonNode body, String field) {
        var n = body.get(field);
        return n == null || n.isNull() || n.asText().isBlank() ? null : n.asText().trim();
    }

    private static Double number(JsonNode body, String field) {
        var n = body.get(field);
        if (n == null || n.isNull() || (n.isTextual() && n.asText().isBlank())) return null;
        if (!n.isNumber()) throw new HttpError(400, field + " must be a number");
        return n.asDouble();
    }

    private static Db.SubActor subActorFrom(JsonNode body, String id) {
        var gender = text(body, "gender");
        return new Db.SubActor(id, required(body, "firstName", "first name"), required(body, "lastName", "last name"),
                text(body, "phone"), "F".equals(gender) || "M".equals(gender) ? gender : null, text(body, "village"));
    }

    private static Db.Facility facilityFrom(JsonNode body, String id, String owner) {
        var outline = new ArrayList<java.util.Map<String, Object>>();
        var raw = body.get("outline");
        if (raw != null && raw.isArray() && raw.size() >= 3)
            for (var p : raw) {
                var point = new LinkedHashMap<String, Object>();
                point.put("lat", p.get("lat").asDouble());
                point.put("lng", p.get("lng").asDouble());
                outline.add(point);
            }
        return new Db.Facility(id, owner, required(body, "name", "name"), text(body, "description"), text(body, "variety"),
                text(body, "plantedOn"), number(body, "latitude"), number(body, "longitude"), outline.isEmpty() ? null : outline);
    }

    private static String known(String account) {
        if (db.account(account) == null) throw new HttpError(404, "unknown account");
        return account;
    }

    // ---- routes ----------------------------------------------------------------------------------------------------

    /** Returns the status; the JSON payload goes in {@code out[0]}. */
    private static int route(String method, String path, JsonNode body, Object[] out) {
        if (path.equals("/api/accounts") && method.equals("GET")) {
            out[0] = db.accounts();
            return 200;
        }
        var m = SUB_ACTORS.matcher(path);
        if (m.matches()) return subActors(method, known(decode(m.group(1))), m.group(2) == null ? null : decode(m.group(2)), body, out);
        m = FACILITIES.matcher(path);
        if (m.matches()) return facilities(method, known(decode(m.group(1))), m.group(2) == null ? null : decode(m.group(2)), body, out);
        throw new HttpError(404, "not found");
    }

    private static int subActors(String method, String a, String id, JsonNode body, Object[] out) {
        switch (method + (id == null ? " list" : " one")) {
            case "GET list" -> {
                out[0] = db.subActors(a);
                return 200;
            }
            case "POST list" -> {
                var s = subActorFrom(body, db.nextId(a, "sub_actors", "grw"));
                db.saveSubActor(a, s);
                integration.pushSubActor(a, s);
                out[0] = s;
                return 201;
            }
            case "PUT one" -> {
                if (db.subActor(a, id) == null) throw new HttpError(404, "sub-actor not found");
                var s = subActorFrom(body, id);
                db.saveSubActor(a, s);
                integration.pushSubActor(a, s);
                out[0] = s;
                return 200;
            }
            case "DELETE one" -> {
                var s = db.subActor(a, id);
                if (s == null) throw new HttpError(404, "sub-actor not found");
                // its facilities go with it: remove them on the platform first (an orchard cannot outlive its owner)
                for (var f : db.facilities(a).stream().filter(f -> id.equals(f.subActorId())).toList()) {
                    db.deleteFacility(a, f.id());
                    integration.removeFacility(a, f);
                }
                db.deleteSubActor(a, id);
                integration.removeSubActor(a, s);
                return 204;
            }
            default -> throw new HttpError(404, "not found");
        }
    }

    private static int facilities(String method, String a, String id, JsonNode body, Object[] out) {
        switch (method + (id == null ? " list" : " one")) {
            case "GET list" -> {
                out[0] = db.facilities(a);
                return 200;
            }
            case "POST list" -> {
                var owner = text(body, "subActorId");
                if (owner != null && db.subActor(a, owner) == null) throw new HttpError(400, "unknown owner");
                var f = facilityFrom(body, db.nextId(a, "facilities", owner != null ? "orc" : "site"), owner);
                db.saveFacility(a, f);
                integration.pushFacility(a, f);
                out[0] = f;
                return 201;
            }
            case "PUT one" -> {
                var current = db.facility(a, id);
                if (current == null) throw new HttpError(404, "facility not found");
                var f = facilityFrom(body, id, current.subActorId()); // the owner never changes: it is part of the facility's identity
                db.saveFacility(a, f);
                integration.pushFacility(a, f);
                out[0] = f;
                return 200;
            }
            case "DELETE one" -> {
                var f = db.facility(a, id);
                if (f == null) throw new HttpError(404, "facility not found");
                db.deleteFacility(a, id);
                integration.removeFacility(a, f);
                return 204;
            }
            default -> throw new HttpError(404, "not found");
        }
    }

    private static String decode(String s) {
        return URLDecoder.decode(s, StandardCharsets.UTF_8);
    }

    // ---- http plumbing ---------------------------------------------------------------------------------------------

    private static void send(HttpExchange ex, int status, byte[] data, String type) throws IOException {
        ex.getResponseHeaders().set("Content-Type", type);
        ex.sendResponseHeaders(status, status == 204 || data == null ? -1 : data.length);
        if (data != null && status != 204) ex.getResponseBody().write(data);
        ex.close();
    }

    private static void handle(HttpExchange ex) throws IOException {
        var method = ex.getRequestMethod();
        var path = ex.getRequestURI().getPath();
        try {
            if (method.equals("GET") && (path.equals("/") || path.equals("/index.html"))) {
                send(ex, 200, Files.readAllBytes(Path.of("public/index.html")), "text/html; charset=utf-8");
                return;
            }
            JsonNode body = JSON.createObjectNode();
            if (method.equals("POST") || method.equals("PUT")) {
                try {
                    var raw = ex.getRequestBody().readAllBytes();
                    if (raw.length > 0) body = JSON.readTree(raw);
                } catch (IOException e) {
                    throw new HttpError(400, "invalid JSON");
                }
            }
            var out = new Object[1];
            var status = route(method, path, body, out);
            send(ex, status, out[0] == null ? null : JSON.writeValueAsBytes(out[0]), "application/json");
        } catch (HttpError e) {
            send(ex, e.status, JSON.writeValueAsBytes(java.util.Map.of("error", e.getMessage())), "application/json");
        } catch (Exception e) {
            e.printStackTrace();
            send(ex, 500, JSON.writeValueAsBytes(java.util.Map.of("error", "server error")), "application/json");
        }
    }

    public static void main(String[] args) throws Exception {
        db = new Db(System.getenv().getOrDefault("DB_PATH", "data/platform.db"), Path.of("data/seed.json"));
        // The integration: with OBENS_API_KEY set, every change is also pushed to Obens; without it, the platform runs alone.
        integration = Integration.fromEnv(db);

        var port = Integer.parseInt(System.getenv().getOrDefault("PORT", "3000"));
        var server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/", App::handle);
        server.setExecutor(Executors.newFixedThreadPool(8));
        server.start();
        System.out.println("Mango Network on http://localhost:" + port);
        // the first load can take a while and must never delay the server: run it beside it
        new Thread(integration::start, "initial-load").start();
    }
}

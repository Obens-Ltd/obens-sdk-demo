package so.obens.demo;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class MappingTest {
    static final String ELEMENT = "66f000000000000000000001";

    final List<Mapping.Event> events = load();

    static List<Mapping.Event> load() {
        try {
            return block("Mango Cooperative");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The seed rows of an account, as the integration turns them into events. */
    @SuppressWarnings("unchecked")
    static List<Mapping.Event> block(String account) throws IOException {
        var seed = new com.fasterxml.jackson.databind.ObjectMapper().readValue(Path.of("data/seed.json").toFile(), Map.class);
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        for (var a : (List<Map<String, Object>>) seed.get("accounts")) {
            if (!account.equals(a.get("name"))) continue;
            var events = new java.util.ArrayList<Mapping.Event>();
            for (var s : (List<Map<String, Object>>) a.get("subActors"))
                events.add(Integration.subActorEvent(mapper.convertValue(s, Db.SubActor.class)));
            for (var f : (List<Map<String, Object>>) a.get("facilities"))
                events.add(Integration.facilityEvent(mapper.convertValue(f, Db.Facility.class)));
            return events;
        }
        throw new IllegalStateException("no account " + account);
    }

    Mapping.Event byId(String appId) {
        return events.stream().filter(e -> appId.equals(e.text("appId"))).findFirst().orElseThrow();
    }

    @Test
    void aGrowerBecomesAFarmerKeyedByTheAppId() {
        assertEquals(Map.of(
                "externalId", "grw-001", "name", "Aminata Traoré", "ref", "+22670000001", "address", "Banfora",
                "gender", "FEMALE", "type", "FARMER", "elementId", ELEMENT),
                Mapping.growerMapper(ELEMENT).apply(byId("grw-001")));
    }

    @Test
    void twoGrowersWithTheSameNameStayTwoFarmers() {
        var a = Mapping.growerMapper(ELEMENT).apply(byId("grw-001"));
        var b = Mapping.growerMapper(ELEMENT).apply(byId("grw-002"));
        assertEquals(a.get("name"), b.get("name"));
        assertNotEquals(a.get("externalId"), b.get("externalId"));
        assertNotEquals(a.get("ref"), b.get("ref"));
    }

    @Test
    void anUnknownGenderIsLeftOutNotSentAsNull() {
        assertFalse(Mapping.growerMapper(ELEMENT).apply(byId("grw-004")).containsKey("gender"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void aTracedOrchardIsAClosedPolygonInLongitudeLatitudeOrder() {
        var orchard = Mapping.orchardMapper().apply(byId("orc-001"));
        var feature = ((List<Map<String, Object>>) ((Map<String, Object>) orchard.get("coordinates")).get("features")).get(0);
        var geometry = (Map<String, Object>) feature.get("geometry");
        var ring = ((List<List<List<Double>>>) geometry.get("coordinates")).get(0);
        assertEquals("Polygon", geometry.get("type"));
        assertEquals(List.of(-4.7615, 10.631), ring.get(0));
        assertEquals(ring.get(0), ring.get(ring.size() - 1));
        assertEquals(5, ring.size());
        assertEquals("Kent orchard north", ((Map<String, Object>) orchard.get("info")).get("name"));
    }

    @Test
    void anOrchardBelongsToItsGrowerOnThePlatform() {
        assertEquals("grw-001", Mapping.orchardMapper().apply(byId("orc-001")).get("subActorExternalId"));
        assertEquals("grw-003", Mapping.orchardMapper().apply(byId("orc-002")).get("subActorExternalId"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void anUntracedOrchardIsOnlyAPoint() {
        var coordinates = (Map<String, Object>) Mapping.orchardMapper().apply(byId("orc-002")).get("coordinates");
        assertEquals(Map.of("type__", "Point", "latitude", 10.6401, "longitude", -4.7512), coordinates);
    }

    @Test
    @SuppressWarnings("unchecked")
    void aSupplierSiteIsAFacilityWithAPosition() throws IOException {
        var site = block("Mango Processor").stream().filter(e -> "site-001".equals(e.text("appId"))).findFirst().orElseThrow();
        var push = Mapping.siteMapper().apply(site);
        assertEquals("site-001", push.get("externalId"));
        assertEquals("Drying plant", ((Map<String, Object>) push.get("info")).get("name"));
        assertEquals(Map.of("type__", "Point", "latitude", 11.1771, "longitude", -4.2979), push.get("coordinates"));
    }
}

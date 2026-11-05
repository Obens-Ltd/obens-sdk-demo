package so.obens.demo;

import so.obens.sdk.Mapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class Mapping {
    private Mapping() {
    }

    /** One event of the field app: {@code { type, action, data }}. */
    public record Event(String type, String action, Map<String, Object> data) {
        public String text(String key) {
            var value = data.get(key);
            return value == null ? null : value.toString();
        }
    }

    // A closed GeoJSON ring: [longitude, latitude] pairs, the first point repeated at the end.
    @SuppressWarnings("unchecked")
    private static List<List<Double>> ring(List<Map<String, Object>> points) {
        var ring = new ArrayList<List<Double>>();
        for (var p : points)
            ring.add(List.of(((Number) p.get("lng")).doubleValue(), ((Number) p.get("lat")).doubleValue()));
        ring.add(ring.get(0));
        return ring;
    }

    /** An orchard traced with 3 points or more becomes a polygon, anything less is only a position. */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> orchardCoordinates(Event event) {
        var outline = (List<Map<String, Object>>) event.data().get("outline");
        if (outline != null && outline.size() >= 3)
            return Map.of(
                    "type__", "Area",
                    "type", "FeatureCollection",
                    "features", List.of(Map.of("type", "Feature",
                            "geometry", Map.of("type", "Polygon", "coordinates", List.of(ring(outline))))));
        return Map.of(
                "type__", "Point",
                "latitude", ((Number) event.data().get("latitude")).doubleValue(),
                "longitude", ((Number) event.data().get("longitude")).doubleValue());
    }

    /** {@code farmersElementId} is the platform list the growers are created in (see {@code client.elements()}). */
    public static Mapper<Event> growerMapper(String farmersElementId) {
        return Mapper.<Event>builder()
                .field("externalId", e -> e.text("appId"))                  // the app's own code: sending it twice never duplicates
                .field("name", e -> e.text("firstName") + " " + e.text("lastName"))
                .field("ref", e -> e.text("phone"))                         // two growers can share a name, the phone tells them apart
                .field("address", e -> e.text("village"))
                .field("gender", e -> e.text("gender") == null ? null : Map.of("M", "MALE", "F", "FEMALE").get(e.text("gender"))) // unknown values are left out
                .constant("type", "FARMER")
                .constant("elementId", farmersElementId)
                .build();
    }

    public static Mapper<Event> orchardMapper() {
        return Mapper.<Event>builder()
                .field("externalId", e -> e.text("appId"))
                .field("info.name", e -> e.text("name"))
                // only what the app knows: "Kent, planted on 2021-06-15", or just "Kent"
                .field("info.description", e -> {
                    var parts = new ArrayList<String>();
                    if (e.text("variety") != null) parts.add(e.text("variety"));
                    if (e.text("plantedOn") != null) parts.add("planted on " + e.text("plantedOn"));
                    return parts.isEmpty() ? null : String.join(", ", parts);
                })
                .field("coordinates", Mapping::orchardCoordinates)
                // the orchard belongs to this grower on the platform (a grower owns many orchards); push the grower first
                .field("subActorExternalId", e -> e.text("growerId"))
                .build();
    }

    /** A supplier's site (a drying plant, a packing house...) is a facility with a position. */
    public static Mapper<Event> siteMapper() {
        return Mapper.<Event>builder()
                .field("externalId", e -> e.text("appId"))
                .field("info.name", e -> e.text("name"))
                .field("info.description", e -> e.text("description"))
                .field("coordinates", e -> Map.of(
                        "type__", "Point",
                        "latitude", ((Number) e.data().get("latitude")).doubleValue(),
                        "longitude", ((Number) e.data().get("longitude")).doubleValue()))
                .build();
    }
}

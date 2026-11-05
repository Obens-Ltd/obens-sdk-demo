from obens_sdk import constant, create_mapper


def _ring(points):
    ring = [[p["lng"], p["lat"]] for p in points]  # GeoJSON order is [longitude, latitude]
    return ring + [ring[0]]                         # and the ring is closed


def orchard_coordinates(event):
    """An orchard traced with 3 points or more becomes a polygon, anything less is only a position."""
    data = event["data"]
    if len(data.get("outline") or []) >= 3:
        return {
            "type__": "Area",
            "type": "FeatureCollection",
            "features": [{"type": "Feature", "geometry": {"type": "Polygon", "coordinates": [_ring(data["outline"])]}}],
        }
    return {"type__": "Point", "latitude": data["latitude"], "longitude": data["longitude"]}


def grower_mapper(farmers_element_id):
    """`farmers_element_id` is the platform list the growers are created in (see `client.elements()`)."""
    return create_mapper({
        "externalId": "data.appId",  # the app's own code: sending it twice never duplicates
        "name": lambda e: f"{e['data']['firstName']} {e['data']['lastName']}",
        "ref": "data.phone",         # two growers can share a name, the phone tells them apart
        "address": "data.village",
        "gender": lambda e: {"M": "MALE", "F": "FEMALE"}.get(e["data"].get("gender")),  # unknown values are left out
        "type": constant("FARMER"),
        "elementId": constant(farmers_element_id),
    })


def orchard_mapper():
    return create_mapper({
        "externalId": "data.appId",
        "info.name": lambda e: e["data"]["name"],
        # only what the app knows: "Kent, planted on 2021-06-15", or just "Kent"
        "info.description": lambda e: ", ".join(filter(None, [e["data"].get("variety"), e["data"].get("plantedOn") and f"planted on {e['data']['plantedOn']}"])) or None,
        "coordinates": orchard_coordinates,
        # the orchard belongs to this grower on the platform (a grower owns many orchards); push the grower first
        "subActorExternalId": "data.growerId",
    })


def site_mapper():
    """A supplier's site (a drying plant, a packing house...) is a facility with a position."""
    return create_mapper({
        "externalId": "data.appId",
        "info.name": "data.name",
        "info.description": "data.description",
        "coordinates": lambda e: {"type__": "Point", "latitude": e["data"]["latitude"], "longitude": e["data"]["longitude"]},
    })

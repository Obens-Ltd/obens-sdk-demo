import json
import unittest
from pathlib import Path

from app.mapping import grower_mapper, orchard_mapper, site_mapper

from app.integration import facility_event, sub_actor_event

SEED = json.loads((Path(__file__).resolve().parent.parent / "data" / "seed.json").read_text(encoding="utf-8"))["accounts"]


def block(name):
    """The seed rows of an account, as the integration turns them into events."""
    a = next(x for x in SEED if x["name"] == name)
    return [sub_actor_event(s) for s in a["subActors"]] + [facility_event(f) for f in a["facilities"]]


EVENTS = block("Mango Cooperative")
ELEMENT = "66f000000000000000000001"


def by_id(app_id):
    return next(e for e in EVENTS if e["data"]["appId"] == app_id)


class MappingTest(unittest.TestCase):
    def test_a_grower_becomes_a_farmer_keyed_by_the_app_id(self):
        self.assertEqual(grower_mapper(ELEMENT)(by_id("grw-001")), {
            "externalId": "grw-001", "name": "Aminata Traoré", "ref": "+22670000001", "address": "Banfora",
            "gender": "FEMALE", "type": "FARMER", "elementId": ELEMENT,
        })

    def test_two_growers_with_the_same_name_stay_two_farmers(self):
        a, b = grower_mapper(ELEMENT)(by_id("grw-001")), grower_mapper(ELEMENT)(by_id("grw-002"))
        self.assertEqual(a["name"], b["name"])
        self.assertNotEqual(a["externalId"], b["externalId"])
        self.assertNotEqual(a["ref"], b["ref"])

    def test_an_unknown_gender_is_left_out_not_sent_as_null(self):
        self.assertNotIn("gender", grower_mapper(ELEMENT)(by_id("grw-004")))

    def test_a_traced_orchard_is_a_closed_polygon_in_longitude_latitude_order(self):
        orchard = orchard_mapper()(by_id("orc-001"))
        polygon = orchard["coordinates"]["features"][0]["geometry"]
        self.assertEqual(polygon["type"], "Polygon")
        self.assertEqual(polygon["coordinates"][0][0], [-4.7615, 10.631])
        self.assertEqual(polygon["coordinates"][0][-1], polygon["coordinates"][0][0])
        self.assertEqual(len(polygon["coordinates"][0]), 5)
        self.assertEqual(orchard["info"]["name"], "Kent orchard north")

    def test_an_orchard_belongs_to_its_grower_on_the_platform(self):
        self.assertEqual(orchard_mapper()(by_id("orc-001"))["subActorExternalId"], "grw-001")
        self.assertEqual(orchard_mapper()(by_id("orc-002"))["subActorExternalId"], "grw-003")

    def test_an_untraced_orchard_is_only_a_point(self):
        self.assertEqual(orchard_mapper()(by_id("orc-002"))["coordinates"],
                         {"type__": "Point", "latitude": 10.6401, "longitude": -4.7512})

    def test_a_supplier_site_is_a_facility_with_a_position(self):
        self.assertEqual(site_mapper()(next(e for e in block("Mango Processor") if e["data"]["appId"] == "site-001")), {
            "externalId": "site-001", "info": {"name": "Drying plant", "description": "Solar drying, 12 tonnes a day"},
            "coordinates": {"type__": "Point", "latitude": 11.1771, "longitude": -4.2979},
        })


if __name__ == "__main__":
    unittest.main()

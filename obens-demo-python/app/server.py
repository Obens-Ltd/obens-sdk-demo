"""The demo platform: a small REST API + the dashboard (public/index.html). Standard library only.

    OBENS_API_KEY=obk_... python -m app.server      then open http://localhost:3000
"""
import json
import os
import re
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import unquote, urlparse

from app.config import connect
from app.db import Store
from app.integration import Integration

ROOT = Path(__file__).resolve().parent.parent
store = Store(os.environ.get("DB_PATH", str(ROOT / "data" / "platform.db")), ROOT / "data" / "seed.json")

# The integration: with OBENS_API_KEY set, every change is also pushed to Obens; without it, the platform runs alone.
integration = Integration(store, connect() if os.environ.get("OBENS_API_KEY") else None)


class HttpError(Exception):
    def __init__(self, status, message):
        super().__init__(message)
        self.status = status


def required(value, name):
    if value is None or not str(value).strip():
        raise HttpError(400, f"{name} is required")
    return str(value).strip()


def optional(v):
    return None if v in (None, "") else v


def number(v, name):
    if v is None:
        return None
    if isinstance(v, bool) or not isinstance(v, (int, float)):
        raise HttpError(400, f"{name} must be a number")
    return v


def sub_actor_from(body, sub_id):
    return {"id": sub_id, "firstName": required(body.get("firstName"), "first name"), "lastName": required(body.get("lastName"), "last name"),
            "phone": optional(body.get("phone")), "gender": body.get("gender") if body.get("gender") in ("F", "M") else None,
            "village": optional(body.get("village"))}


def facility_from(body, fac_id, owner):
    outline = body.get("outline")
    return {"id": fac_id, "subActorId": owner, "name": required(body.get("name"), "name"), "description": optional(body.get("description")),
            "variety": optional(body.get("variety")), "plantedOn": optional(body.get("plantedOn")),
            "latitude": number(optional(body.get("latitude")), "latitude"), "longitude": number(optional(body.get("longitude")), "longitude"),
            "outline": outline if isinstance(outline, list) and len(outline) >= 3 else None}


def known(account_id):
    if store.account(account_id) is None:
        raise HttpError(404, "unknown account")
    return account_id


# (method, path pattern) -> handler(groups, body) -> (status, json)
def list_accounts(g, body):
    return 200, store.accounts()


def list_sub_actors(g, body):
    return 200, store.sub_actors(known(g[0]))


def create_sub_actor(g, body):
    a = known(g[0])
    s = sub_actor_from(body, store.next_id(a, "sub_actors", "grw"))
    store.save_sub_actor(a, s)
    integration.push_sub_actor(a, s)
    return 201, s


def update_sub_actor(g, body):
    a, sub_id = known(g[0]), g[1]
    if store.sub_actor(a, sub_id) is None:
        raise HttpError(404, "sub-actor not found")
    s = sub_actor_from(body, sub_id)
    store.save_sub_actor(a, s)
    integration.push_sub_actor(a, s)
    return 200, s


def delete_sub_actor(g, body):
    a, sub_id = known(g[0]), g[1]
    s = store.sub_actor(a, sub_id)
    if s is None:
        raise HttpError(404, "sub-actor not found")
    # its facilities go with it: remove them on the platform first (an orchard cannot outlive its owner)
    for f in [f for f in store.facilities(a) if f["subActorId"] == sub_id]:
        store.delete_facility(a, f["id"])
        integration.remove_facility(a, f)
    store.delete_sub_actor(a, sub_id)
    integration.remove_sub_actor(a, s)
    return 204, None


def list_facilities(g, body):
    return 200, store.facilities(known(g[0]))


def create_facility(g, body):
    a = known(g[0])
    owner = optional(body.get("subActorId"))
    if owner and store.sub_actor(a, owner) is None:
        raise HttpError(400, "unknown owner")
    f = facility_from(body, store.next_id(a, "facilities", "orc" if owner else "site"), owner)
    store.save_facility(a, f)
    integration.push_facility(a, f)
    return 201, f


def update_facility(g, body):
    a, fac_id = known(g[0]), g[1]
    current = store.facility(a, fac_id)
    if current is None:
        raise HttpError(404, "facility not found")
    f = facility_from(body, fac_id, current["subActorId"])  # the owner never changes: it is part of the facility's identity
    store.save_facility(a, f)
    integration.push_facility(a, f)
    return 200, f


def delete_facility(g, body):
    a, fac_id = known(g[0]), g[1]
    f = store.facility(a, fac_id)
    if f is None:
        raise HttpError(404, "facility not found")
    store.delete_facility(a, fac_id)
    integration.remove_facility(a, f)
    return 204, None


ROUTES = [
    ("GET", r"/api/accounts", list_accounts),
    ("GET", r"/api/accounts/([^/]+)/sub-actors", list_sub_actors),
    ("POST", r"/api/accounts/([^/]+)/sub-actors", create_sub_actor),
    ("PUT", r"/api/accounts/([^/]+)/sub-actors/([^/]+)", update_sub_actor),
    ("DELETE", r"/api/accounts/([^/]+)/sub-actors/([^/]+)", delete_sub_actor),
    ("GET", r"/api/accounts/([^/]+)/facilities", list_facilities),
    ("POST", r"/api/accounts/([^/]+)/facilities", create_facility),
    ("PUT", r"/api/accounts/([^/]+)/facilities/([^/]+)", update_facility),
    ("DELETE", r"/api/accounts/([^/]+)/facilities/([^/]+)", delete_facility),
]
PAGE = (ROOT / "public" / "index.html").read_bytes()


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass

    def send(self, status, payload=None, content_type="application/json"):
        data = payload if isinstance(payload, bytes) else (b"" if payload is None else json.dumps(payload).encode())
        self.send_response(status)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def handle_request(self):
        path = urlparse(self.path).path
        try:
            if self.command == "GET" and path in ("/", "/index.html"):
                return self.send(200, PAGE, "text/html; charset=utf-8")
            for method, pattern, handler in ROUTES:
                match = self.command == method and re.fullmatch(pattern, path)
                if not match:
                    continue
                body = {}
                if method in ("POST", "PUT"):
                    try:
                        body = json.loads(self.rfile.read(int(self.headers.get("Content-Length") or 0)) or b"{}")
                    except ValueError:
                        raise HttpError(400, "invalid JSON")
                status, payload = handler([unquote(x) for x in match.groups()], body)
                return self.send(status, payload)
            raise HttpError(404, "not found")
        except HttpError as e:
            self.send(e.status, {"error": str(e)})
        except Exception as e:  # never leak details
            print("error:", e, flush=True)
            self.send(500, {"error": "server error"})

    do_GET = do_POST = do_PUT = do_DELETE = handle_request


if __name__ == "__main__":
    port = int(os.environ.get("PORT", "3000"))
    server = ThreadingHTTPServer(("0.0.0.0", port), Handler)
    print(f"Mango Network on http://localhost:{port}", flush=True)
    # the first load can take a while and must never delay the server: run it beside it
    threading.Thread(target=integration.start, daemon=True).start()
    server.serve_forever()

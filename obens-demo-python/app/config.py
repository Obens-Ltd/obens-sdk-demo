import os

from obens_sdk import ObensClient


def connect():
    """Connect with the API key (Settings → API keys in the platform)."""
    api_key = os.environ.get("OBENS_API_KEY")
    if not api_key:
        raise SystemExit("Set OBENS_API_KEY (Settings → API keys in the platform)")
    return ObensClient(os.environ.get("OBENS_BASE_URL", "https://api.obens.so/v2/core"), api_key)


def _same(a, b):
    return a.strip().lower() == b.strip().lower()


def find_account(client, ref=None):
    """An ACCOUNT is a company of the supply chain. The key's own account and every account that delegated to it are
    listed by ``accounts.accessible()``; pick one by its name or its id. Without a reference: the key's own account."""
    accounts = client.accounts.accessible()
    if not ref:
        return next((a for a in accounts if a["self"]), accounts[0])
    account = next((a for a in accounts if a["id"] == ref or _same(a["name"], ref)), None)
    if account is None:
        raise SystemExit(f'no access to account "{ref}". Accounts you can act on: ' + ", ".join(a["name"] for a in accounts))
    return account


def acting_as(client, ref=None):
    """(account, client) where the client acts as that account: every call reads and writes that account's own data."""
    account = find_account(client, ref)
    return account, client if account["self"] else client.for_account(account["id"])


def find_element(client, ref):
    """An ELEMENT is a list of the supply chain ("Fresh Mango", "Sliced Mango"...). Each account only sees the lists it
    takes part in, so ask the client that acts as the account. Pick one by name or id: there is no default."""
    elements = client.elements()
    element = next((e for e in elements if e["id"] == ref or _same(e["name"], ref)), None)
    if element is None:
        raise SystemExit(f'no list "{ref}" for this account. Its lists: ' + (", ".join(e["name"] for e in elements) or "none"))
    return element

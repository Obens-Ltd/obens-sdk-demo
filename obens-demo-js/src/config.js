import { ObensClient } from '@obens-ltd/sdk';

// Connect with the API key (Settings → API keys in the platform).
export function connect() {
  const apiKey = process.env.OBENS_API_KEY;
  if (!apiKey) throw new Error('Set OBENS_API_KEY (Settings → API keys in the platform)');
  return new ObensClient({ baseUrl: process.env.OBENS_BASE_URL ?? 'https://api.obens.so/v2/core', apiKey });
}

const same = (a, b) => a.trim().toLowerCase() === b.trim().toLowerCase();

// An ACCOUNT is a company of the supply chain. The key's own account and every account that delegated to it are
// listed by `accounts.accessible()`; pick one by its name or its id. Without a reference you act as the key's own account.
export async function findAccount(client, ref) {
  const accounts = await client.accounts.accessible();
  if (!ref) return accounts.find((a) => a.self) ?? accounts[0];
  const account = accounts.find((a) => a.id === ref || same(a.name, ref));
  if (!account) {
    throw new Error(`no access to account "${ref}". Accounts you can act on: ${accounts.map((a) => a.name).join(', ')}`);
  }
  return account;
}

// The client that acts as that account: every call it makes reads and writes that account's own data.
export async function actingAs(client, ref) {
  const account = await findAccount(client, ref);
  return { account, client: account.self ? client : client.forAccount(account.id) };
}

// An ELEMENT is a list of the supply chain ("Fresh Mango", "Sliced Mango"...). Each account only sees the lists it
// takes part in, so ask the client that acts as the account. Pick one by name or id: there is no default.
export async function findElement(client, ref) {
  const elements = await client.elements();
  const element = elements.find((e) => e.id === ref || same(e.name, ref));
  if (!element) throw new Error(`no list "${ref}" for this account. Its lists: ${elements.map((e) => e.name).join(', ') || 'none'}`);
  return element;
}

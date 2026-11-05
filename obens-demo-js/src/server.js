// The demo platform: a small REST API + the dashboard (public/index.html). Plain Node, no framework.
//   OBENS_API_KEY=obk_... npm start      then open http://localhost:3000
import { createServer } from 'node:http';
import { readFileSync } from 'node:fs';

import { connect } from './config.js';
import { openDb } from './db.js';
import { Integration } from './integration.js';

const root = new URL('..', import.meta.url);
const store = openDb(process.env.DB_PATH ?? new URL('data/platform.db', root).pathname, new URL('data/seed.json', root));

// The integration: with OBENS_API_KEY set, every change is also pushed to Obens; without it, the platform runs alone.
const integration = new Integration(store, process.env.OBENS_API_KEY ? connect() : null);

class HttpError extends Error {
  constructor(status, message) { super(message); this.status = status; }
}
const required = (value, name) => { if (!value || !String(value).trim()) throw new HttpError(400, `${name} is required`); return String(value).trim(); };
const optional = (v) => (v === undefined || v === null || v === '' ? null : v);
const number = (v, name) => { if (v == null) return null; if (typeof v !== 'number' || Number.isNaN(v)) throw new HttpError(400, `${name} must be a number`); return v; };

const subActorFrom = (body, id) => ({
  id, firstName: required(body.firstName, 'first name'), lastName: required(body.lastName, 'last name'),
  phone: optional(body.phone), gender: ['F', 'M'].includes(body.gender) ? body.gender : null, village: optional(body.village),
});
const facilityFrom = (body, id, subActorId) => ({
  id, subActorId, name: required(body.name, 'name'), description: optional(body.description), variety: optional(body.variety),
  plantedOn: optional(body.plantedOn), latitude: number(optional(body.latitude), 'latitude'), longitude: number(optional(body.longitude), 'longitude'),
  outline: Array.isArray(body.outline) && body.outline.length >= 3 ? body.outline : null,
});

// [method, path pattern, handler(params, body) -> [status, json]]
const routes = [
  ['GET', /^\/api\/accounts$/, () => [200, store.accounts()]],

  ['GET', /^\/api\/accounts\/([^/]+)\/sub-actors$/, ([a]) => [200, store.subActors(known(a))]],
  ['POST', /^\/api\/accounts\/([^/]+)\/sub-actors$/, async ([a], body) => {
    const s = subActorFrom(body, store.nextId(known(a), 'sub_actors', 'grw'));
    store.saveSubActor(a, s);
    await integration.pushSubActor(a, s);
    return [201, s];
  }],
  ['PUT', /^\/api\/accounts\/([^/]+)\/sub-actors\/([^/]+)$/, async ([a, id], body) => {
    if (!store.subActor(known(a), id)) throw new HttpError(404, 'sub-actor not found');
    const s = subActorFrom(body, id);
    store.saveSubActor(a, s);
    await integration.pushSubActor(a, s);
    return [200, s];
  }],
  ['DELETE', /^\/api\/accounts\/([^/]+)\/sub-actors\/([^/]+)$/, async ([a, id]) => {
    const s = store.subActor(known(a), id);
    if (!s) throw new HttpError(404, 'sub-actor not found');
    // its facilities go with it: remove them on the platform first (an orchard cannot outlive its owner)
    for (const f of store.facilities(a).filter((f) => f.subActorId === id)) {
      store.deleteFacility(a, f.id);
      await integration.removeFacility(a, f);
    }
    store.deleteSubActor(a, id);
    await integration.removeSubActor(a, s);
    return [204];
  }],

  ['GET', /^\/api\/accounts\/([^/]+)\/facilities$/, ([a]) => [200, store.facilities(known(a))]],
  ['POST', /^\/api\/accounts\/([^/]+)\/facilities$/, async ([a], body) => {
    const owner = optional(body.subActorId);
    if (owner && !store.subActor(known(a), owner)) throw new HttpError(400, 'unknown owner');
    const f = facilityFrom(body, store.nextId(known(a), 'facilities', owner ? 'orc' : 'site'), owner);
    store.saveFacility(a, f);
    await integration.pushFacility(a, f);
    return [201, f];
  }],
  ['PUT', /^\/api\/accounts\/([^/]+)\/facilities\/([^/]+)$/, async ([a, id], body) => {
    const current = store.facility(known(a), id);
    if (!current) throw new HttpError(404, 'facility not found');
    const f = facilityFrom(body, id, current.subActorId); // the owner never changes: it is part of the facility's identity
    store.saveFacility(a, f);
    await integration.pushFacility(a, f);
    return [200, f];
  }],
  ['DELETE', /^\/api\/accounts\/([^/]+)\/facilities\/([^/]+)$/, async ([a, id]) => {
    const f = store.facility(known(a), id);
    if (!f) throw new HttpError(404, 'facility not found');
    store.deleteFacility(a, id);
    await integration.removeFacility(a, f);
    return [204];
  }],
];

function known(accountId) {
  if (!store.account(accountId)) throw new HttpError(404, 'unknown account');
  return accountId;
}

const page = readFileSync(new URL('public/index.html', root));

createServer(async (req, res) => {
  const { pathname } = new URL(req.url, 'http://localhost');
  try {
    if (req.method === 'GET' && (pathname === '/' || pathname === '/index.html')) {
      res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' }).end(page);
      return;
    }
    for (const [method, pattern, handler] of routes) {
      const match = req.method === method && pathname.match(pattern);
      if (!match) continue;
      let body = {};
      if (method === 'POST' || method === 'PUT') {
        const chunks = [];
        for await (const c of req) chunks.push(c);
        try { body = JSON.parse(Buffer.concat(chunks).toString() || '{}'); } catch { throw new HttpError(400, 'invalid JSON'); }
      }
      const [status, json] = await handler(match.slice(1).map(decodeURIComponent), body);
      res.writeHead(status, { 'Content-Type': 'application/json' }).end(json === undefined ? undefined : JSON.stringify(json));
      return;
    }
    throw new HttpError(404, 'not found');
  } catch (e) {
    if (!(e instanceof HttpError)) console.error(e);
    res.writeHead(e.status ?? 500, { 'Content-Type': 'application/json' }).end(JSON.stringify({ error: e.status ? e.message : 'server error' }));
  }
}).listen(Number(process.env.PORT ?? 3000), '0.0.0.0', () => {
  console.log(`Mango Network on http://localhost:${process.env.PORT ?? 3000}`);
  integration.start(); // after listening: the first load can take a while and must never delay the server
});

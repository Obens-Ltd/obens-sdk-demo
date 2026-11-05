import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';

import { growerMapper, orchardMapper, siteMapper } from '../src/mapping.js';

import { facilityEvent, subActorEvent } from '../src/integration.js';

// the seed rows of an account, as the integration turns them into events
const seed = JSON.parse(readFileSync(new URL('../data/seed.json', import.meta.url))).accounts;
const block = (name) => {
  const a = seed.find((x) => x.name === name);
  return [...a.subActors.map(subActorEvent), ...a.facilities.map(facilityEvent)];
};
const events = block('Mango Cooperative');
const element = '66f000000000000000000001';
const byId = (id) => events.find((e) => e.data.appId === id);

test('a grower becomes a farmer keyed by the app id', () => {
  assert.deepEqual(growerMapper(element)(byId('grw-001')), {
    externalId: 'grw-001', name: 'Aminata Traoré', ref: '+22670000001', address: 'Banfora',
    gender: 'FEMALE', type: 'FARMER', elementId: element,
  });
});

test('two growers with the same name stay two farmers', () => {
  const map = growerMapper(element);
  const [a, b] = [map(byId('grw-001')), map(byId('grw-002'))];
  assert.equal(a.name, b.name);
  assert.notEqual(a.externalId, b.externalId);
  assert.notEqual(a.ref, b.ref);
});

test('an unknown gender is left out, not sent as null', () => {
  assert.equal('gender' in growerMapper(element)(byId('grw-004')), false);
});

test('a traced orchard is a closed polygon in [longitude, latitude] order', () => {
  const orchard = orchardMapper()(byId('orc-001'));
  const polygon = orchard.coordinates.features[0].geometry;
  assert.equal(polygon.type, 'Polygon');
  assert.deepEqual(polygon.coordinates[0][0], [-4.7615, 10.631]);
  assert.deepEqual(polygon.coordinates[0].at(-1), polygon.coordinates[0][0]);
  assert.equal(polygon.coordinates[0].length, 5);
  assert.equal(orchard.info.name, 'Kent orchard north');
});

test('an orchard belongs to its grower on the platform', () => {
  assert.equal(orchardMapper()(byId('orc-001')).subActorExternalId, 'grw-001');
  assert.equal(orchardMapper()(byId('orc-002')).subActorExternalId, 'grw-003');
});

test('an untraced orchard is only a point', () => {
  assert.deepEqual(orchardMapper()(byId('orc-002')).coordinates, { type__: 'Point', latitude: 10.6401, longitude: -4.7512 });
});

test('a supplier site is a facility with a position', () => {
  assert.deepEqual(siteMapper()(block('Mango Processor').find((e) => e.data.appId === 'site-001')), {
    externalId: 'site-001', info: { name: 'Drying plant', description: 'Solar drying, 12 tonnes a day' },
    coordinates: { type__: 'Point', latitude: 11.1771, longitude: -4.2979 },
  });
});

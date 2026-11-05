import { createMapper, constant } from '@obens-ltd/sdk';

// A closed GeoJSON ring: [longitude, latitude] pairs, the first point repeated at the end.
const ring = (points) => {
  const r = points.map((p) => [p.lng, p.lat]);
  return [...r, r[0]];
};

// An orchard traced with 3 points or more becomes a polygon, anything less is only a position.
export const orchardCoordinates = ({ data }) =>
  data.outline?.length >= 3
    ? {
        type__: 'Area',
        type: 'FeatureCollection',
        features: [{ type: 'Feature', geometry: { type: 'Polygon', coordinates: [ring(data.outline)] } }],
      }
    : { type__: 'Point', latitude: data.latitude, longitude: data.longitude };

// `farmersElementId` is the platform list the growers are created in (see `client.elements()`).
export const growerMapper = (farmersElementId) =>
  createMapper({
    externalId: 'data.appId', // the app's own code: sending it twice never duplicates
    name: ({ data }) => `${data.firstName} ${data.lastName}`,
    ref: 'data.phone', // two growers can share a name, the phone tells them apart
    address: 'data.village',
    gender: ({ data }) => ({ M: 'MALE', F: 'FEMALE' })[data.gender], // unknown values are left out
    type: constant('FARMER'),
    elementId: constant(farmersElementId),
  });

export const orchardMapper = () =>
  createMapper({
    externalId: 'data.appId',
    'info.name': ({ data }) => data.name,
    // only what the app knows: "Kent, planted on 2021-06-15", or just "Kent"
    'info.description': ({ data }) =>
      [data.variety, data.plantedOn && `planted on ${data.plantedOn}`].filter(Boolean).join(', ') || undefined,
    coordinates: orchardCoordinates,
    // the orchard belongs to this grower on the platform (a grower owns many orchards); push the grower first
    subActorExternalId: 'data.growerId',
  });

// A supplier's site (a drying plant, a packing house...) is a facility with a position.
export const siteMapper = () =>
  createMapper({
    externalId: 'data.appId',
    'info.name': 'data.name',
    'info.description': 'data.description',
    coordinates: ({ data }) => ({ type__: 'Point', latitude: data.latitude, longitude: data.longitude }),
  });

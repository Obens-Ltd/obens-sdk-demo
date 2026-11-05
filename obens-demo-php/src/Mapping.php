<?php

declare(strict_types=1);

namespace Demo;

use Obens\Sdk\Mapper;

final class Mapping
{
    /** GeoJSON order is [longitude, latitude] and the ring is closed. */
    private static function ring(array $points): array
    {
        $ring = array_map(fn ($p) => [$p['lng'], $p['lat']], $points);
        $ring[] = $ring[0];
        return $ring;
    }

    /** An orchard traced with 3 points or more becomes a polygon, anything less is only a position. */
    public static function orchardCoordinates(array $event): array
    {
        $data = $event['data'];
        if (count($data['outline'] ?? []) >= 3) {
            return [
                'type__' => 'Area',
                'type' => 'FeatureCollection',
                'features' => [[
                    'type' => 'Feature',
                    'geometry' => ['type' => 'Polygon', 'coordinates' => [self::ring($data['outline'])]],
                ]],
            ];
        }
        return ['type__' => 'Point', 'latitude' => $data['latitude'], 'longitude' => $data['longitude']];
    }

    /** `$farmersElementId` is the platform list the growers are created in (see `$client->elements()`). */
    public static function growerMapper(string $farmersElementId): \Closure
    {
        return Mapper::create([
            'externalId' => 'data.appId',   // the app's own code: sending it twice never duplicates
            'name' => fn ($e) => $e['data']['firstName'] . ' ' . $e['data']['lastName'],
            'ref' => 'data.phone',          // two growers can share a name, the phone tells them apart
            'address' => 'data.village',
            // unknown values are left out (null is skipped), never sent as null
            'gender' => fn ($e) => ['M' => 'MALE', 'F' => 'FEMALE'][$e['data']['gender'] ?? ''] ?? null,
            'type' => Mapper::constant('FARMER'),
            'elementId' => Mapper::constant($farmersElementId),
        ]);
    }

    public static function orchardMapper(): \Closure
    {
        return Mapper::create([
            'externalId' => 'data.appId',
            'info.name' => fn ($e) => $e['data']['name'],
            // only what the app knows: "Kent, planted on 2021-06-15", or just "Kent"
            'info.description' => fn ($e) => implode(', ', array_filter([
                $e['data']['variety'] ?? null,
                isset($e['data']['plantedOn']) ? 'planted on ' . $e['data']['plantedOn'] : null,
            ])) ?: null,
            'coordinates' => fn ($e) => self::orchardCoordinates($e),
            // the orchard belongs to this grower on the platform (a grower owns many orchards); push the grower first
            'subActorExternalId' => 'data.growerId',
        ]);
    }

    /** A supplier's site (a drying plant, a packing house...) is a facility with a position. */
    public static function siteMapper(): \Closure
    {
        return Mapper::create([
            'externalId' => 'data.appId',
            'info.name' => 'data.name',
            'info.description' => 'data.description',
            'coordinates' => fn ($e) => [
                'type__' => 'Point',
                'latitude' => $e['data']['latitude'],
                'longitude' => $e['data']['longitude'],
            ],
        ]);
    }
}

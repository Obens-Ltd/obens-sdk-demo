<?php

declare(strict_types=1);

namespace Demo\Tests;

use Demo\Integration;
use Demo\Mapping;
use PHPUnit\Framework\TestCase;

final class MappingTest extends TestCase
{
    private const ELEMENT = '66f000000000000000000001';

    /** The seed rows of an account, as the integration turns them into events. */
    private static function events(string $account = 'Mango Cooperative'): array
    {
        $seed = json_decode((string) file_get_contents(__DIR__ . '/../data/seed.json'), true);
        foreach ($seed['accounts'] as $a) {
            if ($a['name'] === $account) {
                return [...array_map([Integration::class, 'subActorEvent'], $a['subActors']),
                    ...array_map([Integration::class, 'facilityEvent'], $a['facilities'])];
            }
        }
        return [];
    }

    private static function byId(string $appId): array
    {
        foreach (self::events() as $event) {
            if ($event['data']['appId'] === $appId) {
                return $event;
            }
        }
        throw new \OutOfBoundsException($appId);
    }

    public function testAGrowerBecomesAFarmerKeyedByTheAppId(): void
    {
        $this->assertSame([
            'externalId' => 'grw-001', 'name' => 'Aminata Traoré', 'ref' => '+22670000001', 'address' => 'Banfora',
            'gender' => 'FEMALE', 'type' => 'FARMER', 'elementId' => self::ELEMENT,
        ], Mapping::growerMapper(self::ELEMENT)(self::byId('grw-001')));
    }

    public function testTwoGrowersWithTheSameNameStayTwoFarmers(): void
    {
        $map = Mapping::growerMapper(self::ELEMENT);
        $a = $map(self::byId('grw-001'));
        $b = $map(self::byId('grw-002'));
        $this->assertSame($a['name'], $b['name']);
        $this->assertNotSame($a['externalId'], $b['externalId']);
        $this->assertNotSame($a['ref'], $b['ref']);
    }

    public function testAnUnknownGenderIsLeftOutNotSentAsNull(): void
    {
        $this->assertArrayNotHasKey('gender', Mapping::growerMapper(self::ELEMENT)(self::byId('grw-004')));
    }

    public function testATracedOrchardIsAClosedPolygonInLongitudeLatitudeOrder(): void
    {
        $orchard = Mapping::orchardMapper()(self::byId('orc-001'));
        $polygon = $orchard['coordinates']['features'][0]['geometry'];
        $this->assertSame('Polygon', $polygon['type']);
        $this->assertSame([-4.7615, 10.631], $polygon['coordinates'][0][0]);
        $this->assertSame($polygon['coordinates'][0][0], $polygon['coordinates'][0][4]);
        $this->assertCount(5, $polygon['coordinates'][0]);
        $this->assertSame('Kent orchard north', $orchard['info']['name']);
    }

    public function testAnOrchardBelongsToItsGrowerOnThePlatform(): void
    {
        $this->assertSame('grw-001', Mapping::orchardMapper()(self::byId('orc-001'))['subActorExternalId']);
        $this->assertSame('grw-003', Mapping::orchardMapper()(self::byId('orc-002'))['subActorExternalId']);
    }

    public function testAnUntracedOrchardIsOnlyAPoint(): void
    {
        $this->assertSame(
            ['type__' => 'Point', 'latitude' => 10.6401, 'longitude' => -4.7512],
            Mapping::orchardMapper()(self::byId('orc-002'))['coordinates']
        );
    }

    public function testASupplierSiteIsAFacilityWithAPosition(): void
    {
        $site = array_values(array_filter(self::events('Mango Processor'), fn ($e) => $e['data']['appId'] === 'site-001'))[0];
        $this->assertSame([
            'externalId' => 'site-001',
            'info' => ['name' => 'Drying plant', 'description' => 'Solar drying, 12 tonnes a day'],
            'coordinates' => ['type__' => 'Point', 'latitude' => 11.1771, 'longitude' => -4.2979],
        ], Mapping::siteMapper()($site));
    }
}

<?php

/**
 * The demo platform: a small REST API + the dashboard (index.html). Plain PHP, no framework. Run by PHP's built-in
 * server as its router:   php -S 0.0.0.0:3000 public/index.php
 */

declare(strict_types=1);

require __DIR__ . '/../vendor/autoload.php';

use Demo\Db;
use Demo\Integration;

final class HttpError extends \RuntimeException
{
    public function __construct(public int $status, string $message)
    {
        parent::__construct($message);
    }
}

function required(mixed $value, string $name): string
{
    if ($value === null || trim((string) $value) === '') {
        throw new HttpError(400, "$name is required");
    }
    return trim((string) $value);
}

function optional(mixed $v): mixed
{
    return $v === null || $v === '' ? null : $v;
}

function number(mixed $v, string $name): int|float|null
{
    if ($v === null) {
        return null;
    }
    if (!is_int($v) && !is_float($v)) {
        throw new HttpError(400, "$name must be a number");
    }
    return $v;
}

function subActorFrom(array $b, string $id): array
{
    return ['id' => $id, 'firstName' => required($b['firstName'] ?? null, 'first name'), 'lastName' => required($b['lastName'] ?? null, 'last name'),
        'phone' => optional($b['phone'] ?? null), 'gender' => in_array($b['gender'] ?? null, ['F', 'M'], true) ? $b['gender'] : null,
        'village' => optional($b['village'] ?? null)];
}

function facilityFrom(array $b, string $id, ?string $owner): array
{
    $outline = $b['outline'] ?? null;
    return ['id' => $id, 'subActorId' => $owner, 'name' => required($b['name'] ?? null, 'name'),
        'description' => optional($b['description'] ?? null), 'variety' => optional($b['variety'] ?? null),
        'plantedOn' => optional($b['plantedOn'] ?? null),
        'latitude' => number(optional($b['latitude'] ?? null), 'latitude'), 'longitude' => number(optional($b['longitude'] ?? null), 'longitude'),
        'outline' => is_array($outline) && count($outline) >= 3 ? $outline : null];
}

$path = rawurldecode(parse_url($_SERVER['REQUEST_URI'], PHP_URL_PATH));
$method = $_SERVER['REQUEST_METHOD'];

function reply(int $status, mixed $payload = null): never
{
    http_response_code($status);
    if ($payload !== null) {
        header('Content-Type: application/json');
        echo json_encode($payload, JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES | JSON_PRESERVE_ZERO_FRACTION);
    }
    exit;
}

if ($method === 'GET' && ($path === '/' || $path === '/index.html')) {
    header('Content-Type: text/html; charset=utf-8');
    readfile(__DIR__ . '/index.html');
    exit;
}

try {
    $db = new Db(getenv('DB_PATH') ?: __DIR__ . '/../data/platform.db', __DIR__ . '/../data/seed.json');
    // The integration: with OBENS_API_KEY set, every change is also pushed to Obens; without it, the platform runs alone.
    $integration = Integration::fromEnv($db);

    $known = function (string $id) use ($db): string {
        if ($db->account($id) === null) {
            throw new HttpError(404, 'unknown account');
        }
        return $id;
    };
    $body = [];
    if (in_array($method, ['POST', 'PUT'], true)) {
        $body = json_decode(file_get_contents('php://input') ?: '{}', true);
        if (!is_array($body)) {
            throw new HttpError(400, 'invalid JSON');
        }
    }

    if ($path === '/api/accounts' && $method === 'GET') {
        reply(200, $db->accounts());
    }

    if (preg_match('#^/api/accounts/([^/]+)/sub-actors(?:/([^/]+))?$#', $path, $m)) {
        $a = $known($m[1]);
        $id = $m[2] ?? null;
        if ($id === null && $method === 'GET') {
            reply(200, $db->subActors($a));
        }
        if ($id === null && $method === 'POST') {
            $s = subActorFrom($body, $db->nextId($a, 'sub_actors', 'grw'));
            $db->saveSubActor($a, $s);
            $integration->pushSubActor($a, $s);
            reply(201, $s);
        }
        if ($id !== null && $method === 'PUT') {
            if ($db->subActorById($a, $id) === null) {
                throw new HttpError(404, 'sub-actor not found');
            }
            $s = subActorFrom($body, $id);
            $db->saveSubActor($a, $s);
            $integration->pushSubActor($a, $s);
            reply(200, $s);
        }
        if ($id !== null && $method === 'DELETE') {
            $s = $db->subActorById($a, $id) ?? throw new HttpError(404, 'sub-actor not found');
            // its facilities go with it: remove them on the platform first (an orchard cannot outlive its owner)
            foreach (array_filter($db->facilities($a), fn ($f) => $f['subActorId'] === $id) as $f) {
                $db->deleteFacility($a, $f['id']);
                $integration->removeFacility($a, $f);
            }
            $db->deleteSubActor($a, $id);
            $integration->removeSubActor($a, $s);
            reply(204);
        }
    }

    if (preg_match('#^/api/accounts/([^/]+)/facilities(?:/([^/]+))?$#', $path, $m)) {
        $a = $known($m[1]);
        $id = $m[2] ?? null;
        if ($id === null && $method === 'GET') {
            reply(200, $db->facilities($a));
        }
        if ($id === null && $method === 'POST') {
            $owner = optional($body['subActorId'] ?? null);
            if ($owner && $db->subActorById($a, $owner) === null) {
                throw new HttpError(400, 'unknown owner');
            }
            $f = facilityFrom($body, $db->nextId($a, 'facilities', $owner ? 'orc' : 'site'), $owner);
            $db->saveFacility($a, $f);
            $integration->pushFacility($a, $f);
            reply(201, $f);
        }
        if ($id !== null && $method === 'PUT') {
            $current = $db->facilityById($a, $id) ?? throw new HttpError(404, 'facility not found');
            $f = facilityFrom($body, $id, $current['subActorId']); // the owner never changes: it is part of the facility's identity
            $db->saveFacility($a, $f);
            $integration->pushFacility($a, $f);
            reply(200, $f);
        }
        if ($id !== null && $method === 'DELETE') {
            $f = $db->facilityById($a, $id) ?? throw new HttpError(404, 'facility not found');
            $db->deleteFacility($a, $id);
            $integration->removeFacility($a, $f);
            reply(204);
        }
    }

    throw new HttpError(404, 'not found');
} catch (HttpError $e) {
    reply($e->status, ['error' => $e->getMessage()]);
} catch (\Throwable $e) {
    error_log((string) $e);
    reply(500, ['error' => 'server error']);
}

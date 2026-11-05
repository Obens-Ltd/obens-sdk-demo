<?php

/**
 * The initial load: pushes every sub-actor and facility of every account to Obens, once, when the platform starts.
 * PHP's built-in server cannot run a task beside the requests, so the container starts this script next to it
 * (see Dockerfile). Sending the same data again only reports "updated".
 *
 *     OBENS_API_KEY=obk_... php bin/sync.php
 */

declare(strict_types=1);

require __DIR__ . '/../vendor/autoload.php';

use Demo\Db;
use Demo\Integration;

try {
    $db = new Db(getenv('DB_PATH') ?: __DIR__ . '/../data/platform.db', __DIR__ . '/../data/seed.json');
    Integration::fromEnv($db)->start();
} catch (\Throwable $e) {
    fwrite(STDERR, '[sync] ' . $e->getMessage() . "\n");
    exit(1);
}

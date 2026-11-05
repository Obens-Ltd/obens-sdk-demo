<?php

declare(strict_types=1);

namespace Demo;

use Obens\Sdk\ObensClient;

final class Config
{
    /** Connect with the API key (Settings → API keys in the platform). */
    public static function connect(): ObensClient
    {
        $apiKey = getenv('OBENS_API_KEY');
        if (!$apiKey) {
            throw new \RuntimeException('Set OBENS_API_KEY (Settings → API keys in the platform)');
        }
        return new ObensClient(getenv('OBENS_BASE_URL') ?: 'https://api.obens.so/v2/core', $apiKey);
    }

    private static function same(string $a, string $b): bool
    {
        return strtolower(trim($a)) === strtolower(trim($b));
    }

    /**
     * An ACCOUNT is a company of the supply chain. The key's own account and every account that delegated to it are
     * listed by `accounts->accessible()`; pick one by its name or its id. Without a reference: the key's own account.
     *
     * @return array{id:string,name:string,self:bool}
     */
    public static function findAccount(ObensClient $client, ?string $ref = null): array
    {
        $accounts = $client->accounts->accessible();
        foreach ($accounts as $account) {
            if ($ref === null || $ref === '' ? $account['self'] : ($account['id'] === $ref || self::same($account['name'], $ref))) {
                return $account;
            }
        }
        throw new \RuntimeException("no access to account \"$ref\". Accounts you can act on: "
            . implode(', ', array_column($accounts, 'name')));
    }

    /**
     * The client that acts as that account: every call it makes reads and writes that account's own data.
     *
     * @return array{0:array{id:string,name:string,self:bool},1:ObensClient}
     */
    public static function actingAs(ObensClient $client, ?string $ref = null): array
    {
        $account = self::findAccount($client, $ref);
        return [$account, $account['self'] ? $client : $client->forAccount($account['id'])];
    }

    /**
     * An ELEMENT is a list of the supply chain ("Fresh Mango", "Sliced Mango"...). Each account only sees the lists it
     * takes part in, so ask the client that acts as the account. Pick one by name or id: there is no default.
     *
     * @return array{id:string,name:string}
     */
    public static function findElement(ObensClient $client, string $ref): array
    {
        $elements = $client->elements();
        foreach ($elements as $element) {
            if ($element['id'] === $ref || self::same($element['name'], $ref)) {
                return $element;
            }
        }
        throw new \RuntimeException("no list \"$ref\" for this account. Its lists: "
            . (implode(', ', array_column($elements, 'name')) ?: 'none'));
    }
}

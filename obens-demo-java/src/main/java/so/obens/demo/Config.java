package so.obens.demo;

import so.obens.sdk.AccountRef;
import so.obens.sdk.ElementRef;
import so.obens.sdk.ObensClient;


final class Config {
    private Config() {
    }

    /** Connect with the API key (Settings → API keys in the platform). */
    static ObensClient connect() {
        var apiKey = System.getenv("OBENS_API_KEY");
        if (apiKey == null || apiKey.isBlank())
            throw new IllegalStateException("Set OBENS_API_KEY (Settings → API keys in the platform)");
        var baseUrl = System.getenv().getOrDefault("OBENS_BASE_URL", "https://api.obens.so/v2/core");
        return ObensClient.builder(baseUrl, apiKey).build();
    }

    /** An account and the client that acts as it: every call it makes reads and writes that account's own data. */
    record Acting(AccountRef account, ObensClient client) {
    }

    /**
     * An ACCOUNT is a company of the supply chain. The key's own account and every account that delegated to it are
     * listed by {@code accounts().accessible()}; pick one by its name or its id. Without a reference: the key's own account.
     */
    static AccountRef findAccount(ObensClient client, String ref) {
        var accounts = client.accounts().accessible();
        return accounts.stream()
                .filter(a -> ref == null || ref.isBlank() ? a.self() : a.id().equals(ref) || a.name().equalsIgnoreCase(ref.trim()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("no access to account \"" + ref + "\". Accounts you can act on: "
                        + String.join(", ", accounts.stream().map(AccountRef::name).toList())));
    }

    static Acting actingAs(ObensClient client, String ref) {
        var account = findAccount(client, ref);
        return new Acting(account, account.self() ? client : client.forAccount(account.id()));
    }

    /**
     * An ELEMENT is a list of the supply chain ("Fresh Mango", "Sliced Mango"...). Each account only sees the lists it
     * takes part in, so ask the client that acts as the account. Pick one by name or id: there is no default.
     */
    static ElementRef findElement(ObensClient client, String ref) {
        var elements = client.elements();
        return elements.stream()
                .filter(e -> e.id().equals(ref) || e.name().equalsIgnoreCase(ref.trim()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("no list \"" + ref + "\" for this account. Its lists: "
                        + (elements.isEmpty() ? "none" : String.join(", ", elements.stream().map(ElementRef::name).toList()))));
    }
}

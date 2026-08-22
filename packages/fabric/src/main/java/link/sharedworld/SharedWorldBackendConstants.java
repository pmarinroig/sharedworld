package link.sharedworld;

final class SharedWorldBackendConstants {
    /**
     * 0.4.6: the self-hosted backend (lane D). Releases up to 0.4.5 carry the
     * Cloudflare worker address, which keeps serving them as a forwarder to
     * this host; the worker also stays the download relay for everyone.
     */
    static final String DEFAULT_BASE_URL = "https://api.sharedworld.net";
    static final String BACKEND_URL_SYSTEM_PROPERTY = "sharedworld.backendUrl";

    private SharedWorldBackendConstants() {
    }
}

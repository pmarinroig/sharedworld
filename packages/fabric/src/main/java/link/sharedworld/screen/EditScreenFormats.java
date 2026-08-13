package link.sharedworld.screen;

import link.sharedworld.SharedWorldText;
import link.sharedworld.api.SharedWorldModels.StorageUsageSummaryDto;
import link.sharedworld.api.SharedWorldModels.WorldDetailsDto;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/** Pure display formatting for the edit screen's detail panels. */
final class EditScreenFormats {
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private EditScreenFormats() {
    }

    static String formatRole(String role) {
        return SharedWorldText.string("owner".equalsIgnoreCase(role)
                ? "screen.sharedworld.role_owner"
                : "screen.sharedworld.role_member");
    }

    static String blankOr(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    static String formatTimestamp(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        try {
            return DATE_FORMAT.format(Instant.parse(value).atZone(ZoneId.systemDefault()));
        } catch (Exception ignored) {
            return value;
        }
    }

    static String formatBytes(long value) {
        if (value >= 1024L * 1024L * 1024L) {
            return SharedWorldText.string("screen.sharedworld.size_gb", String.format(Locale.ROOT, "%.1f", value / (1024.0 * 1024.0 * 1024.0)));
        }
        if (value >= 1024L * 1024L) {
            return SharedWorldText.string("screen.sharedworld.size_mb", String.format(Locale.ROOT, "%.1f", value / (1024.0 * 1024.0)));
        }
        if (value >= 1024L) {
            return SharedWorldText.string("screen.sharedworld.size_kb", String.format(Locale.ROOT, "%.1f", value / 1024.0));
        }
        return SharedWorldText.string("screen.sharedworld.size_b", value);
    }

    /** Quota fill fraction (0..1+), or null when the provider reported no quota. */
    static Double quotaFraction(StorageUsageSummaryDto usage) {
        if (usage == null || usage.quotaTotalBytes() == null || usage.quotaTotalBytes() <= 0 || usage.quotaUsedBytes() == null) {
            return null;
        }
        return usage.quotaUsedBytes() / (double) usage.quotaTotalBytes();
    }

    static String formatQuota(StorageUsageSummaryDto usage) {
        if (usage == null || usage.quotaTotalBytes() == null || usage.quotaTotalBytes() <= 0) {
            return SharedWorldText.string("screen.sharedworld.unknown");
        }
        return formatBytes(usage.quotaUsedBytes() == null ? 0L : usage.quotaUsedBytes()) + " / " + formatBytes(usage.quotaTotalBytes());
    }

    static String formatUsedByWorld(StorageUsageSummaryDto usage) {
        if (usage == null) {
            return SharedWorldText.string("screen.sharedworld.unknown");
        }
        return formatBytes(usage.usedBytes());
    }

    static String formatStorageProvider(WorldDetailsDto details) {
        if (details == null || details.storageProvider() == null || details.storageProvider().isBlank()) {
            return SharedWorldText.string("screen.sharedworld.unknown");
        }
        return "google-drive".equalsIgnoreCase(details.storageProvider())
                ? SharedWorldText.string("screen.sharedworld.storage_provider_google_drive")
                : details.storageProvider();
    }

    static String formatStorageAccount(WorldDetailsDto details, StorageUsageSummaryDto usage) {
        String account = details != null && details.storageLinked()
                ? blankOr(details.storageAccountEmail(), SharedWorldText.string("screen.sharedworld.storage_linked"))
                : null;
        if ((account == null || account.isBlank()) && usage != null && usage.accountEmail() != null && !usage.accountEmail().isBlank()) {
            account = usage.accountEmail();
        }
        return blankOr(account, SharedWorldText.string("screen.sharedworld.storage_not_linked"));
    }
}

package link.sharedworld;

import com.mojang.blaze3d.platform.NativeImage;
import link.sharedworld.api.SharedWorldModels.SignedBlobUrlDto;
import link.sharedworld.api.SharedWorldModels.WorldSummaryDto;
import link.sharedworld.versioned.FileDialogCompat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.FaviconTexture;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Base64;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

public final class SharedWorldCustomIconStore {
    /**
     * The world list polls {@link #resolveCachedIcon} every frame, so a
     * download that keeps failing must be remembered: without a cool-down a
     * broken icon turns into an infinite request-per-frame retry loop
     * against the backend.
     */
    private static final long FAILED_DOWNLOAD_RETRY_MS = 60_000L;

    private final Map<String, CompletableFuture<Path>> inFlightDownloads = new ConcurrentHashMap<>();
    private final Map<String, Long> failedDownloadsAtMs = new ConcurrentHashMap<>();

    public Path resolveCachedIcon(WorldSummaryDto world) {
        return resolveCachedIcon(world.customIconStorageKey(), world.customIconDownload());
    }

    /** The cached icon file for storageKey, or null (kicking off a download when a URL is known). */
    public Path resolveCachedIcon(String storageKey, SignedBlobUrlDto download) {
        if (storageKey == null || storageKey.isBlank()) {
            return null;
        }
        Path cached = this.cachedIconPath(storageKey);
        if (Files.isRegularFile(cached)) {
            return cached;
        }
        if (download != null) {
            this.downloadIfNeeded(storageKey, download, cached);
        }
        return null;
    }

    public void uploadPreview(FaviconTexture texture, Path iconPath) {
        if (iconPath == null || !Files.isRegularFile(iconPath)) {
            texture.clear();
            return;
        }

        try (InputStream input = Files.newInputStream(iconPath);
             NativeImage image = NativeImage.read(input)) {
            texture.upload(image);
        } catch (IOException | IllegalArgumentException exception) {
            texture.clear();
        }
    }

    /**
     * Opens the PNG picker. {@code onChosen} runs on the client thread with the
     * validated icon and is skipped when the player cancels; validation failures go
     * to {@code onError}. The dialog may answer after this returns (26.3+).
     */
    public void chooseIcon(Consumer<SelectedIcon> onChosen, Consumer<Exception> onError) {
        FileDialogCompat.openPngFile("Choose Shared World Icon", selectedPath -> {
            if (selectedPath == null) {
                return;
            }
            try {
                Path path = Path.of(selectedPath);
                validateIcon(path);
                onChosen.accept(new SelectedIcon(path, link.sharedworld.sync.LocalWorldHasher.hashFile(path)));
            } catch (Exception exception) {
                onError.accept(exception);
            }
        });
    }

    public void validateIcon(Path path) throws IOException {
        if (path == null || !Files.isRegularFile(path)) {
            throw new IOException("Custom icon file was not found.");
        }
        if (!path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".png")) {
            throw new IOException("Custom icon must be a PNG file.");
        }

        try (InputStream input = Files.newInputStream(path);
             NativeImage image = NativeImage.read(input)) {
            if (image.getWidth() != 64 || image.getHeight() != 64) {
                throw new IOException("Custom icon must be exactly 64x64 pixels.");
            }
        } catch (IllegalArgumentException exception) {
            throw new IOException("Custom icon must be a valid PNG image.", exception);
        }
    }

    public Path cachedIconPath(String storageKey) {
        String safeName = storageKey.replaceAll("[^a-zA-Z0-9._-]", "_");
        return Minecraft.getInstance().gameDirectory.toPath()
                .resolve("sharedworld")
                .resolve("icons")
                .resolve(safeName);
    }

    public String encodePngBase64(Path path) throws IOException {
        validateIcon(path);
        return Base64.getEncoder().encodeToString(Files.readAllBytes(path));
    }

    private void downloadIfNeeded(String storageKey, SignedBlobUrlDto signedUrl, Path cached) {
        Long failedAt = this.failedDownloadsAtMs.get(storageKey);
        if (failedAt != null && System.currentTimeMillis() - failedAt < FAILED_DOWNLOAD_RETRY_MS) {
            return;
        }
        this.inFlightDownloads.computeIfAbsent(storageKey, ignoredKey -> CompletableFuture.supplyAsync(() -> {
            try {
                Files.createDirectories(cached.getParent());
                Path temp = cached.resolveSibling(cached.getFileName() + ".tmp");
                SharedWorldClient.apiClient().downloadRawBlobToFile(signedUrl, temp);
                Files.move(temp, cached, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                this.failedDownloadsAtMs.remove(storageKey);
                return cached;
            } catch (Exception exception) {
                this.failedDownloadsAtMs.put(storageKey, System.currentTimeMillis());
                SharedWorldClient.LOGGER.warn("Failed to cache Shared World custom icon {}", storageKey, exception);
                return null;
            }
        }, SharedWorldClient.ioExecutor()).whenComplete((ignoredResult, error) -> this.inFlightDownloads.remove(storageKey)));
    }

    public record SelectedIcon(Path path, String hash) {
    }
}

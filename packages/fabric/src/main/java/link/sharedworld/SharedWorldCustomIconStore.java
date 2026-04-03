package link.sharedworld;

import com.mojang.blaze3d.platform.NativeImage;
import link.sharedworld.api.SharedWorldModels.SignedBlobUrlDto;
import link.sharedworld.api.SharedWorldModels.WorldSummaryDto;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.FaviconTexture;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.tinyfd.TinyFileDialogs;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

public final class SharedWorldCustomIconStore {
    private final Map<String, CompletableFuture<Path>> inFlightDownloads = new ConcurrentHashMap<>();

    public Path resolveCachedIcon(WorldSummaryDto world) {
        if (world.customIconStorageKey() == null || world.customIconStorageKey().isBlank()) {
            return null;
        }
        Path cached = this.cachedIconPath(world.customIconStorageKey());
        if (Files.isRegularFile(cached)) {
            return cached;
        }
        if (world.customIconDownload() != null) {
            this.downloadIfNeeded(world.customIconStorageKey(), world.customIconDownload(), cached);
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

    public SelectedIcon chooseIcon() throws IOException {
        String selectedPath;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            PointerBuffer patterns = stack.mallocPointer(1);
            patterns.put(stack.UTF8("*.png"));
            patterns.flip();
            selectedPath = TinyFileDialogs.tinyfd_openFileDialog(
                    "Choose Shared World Icon",
                    "",
                    patterns,
                    null,
                    false
            );
        }
        if (selectedPath == null || selectedPath.isBlank()) {
            return null;
        }

        Path path = Path.of(selectedPath);
        validateIcon(path);
        return new SelectedIcon(path, sha256(path));
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
        this.inFlightDownloads.computeIfAbsent(storageKey, ignoredKey -> CompletableFuture.supplyAsync(() -> {
            try {
                Files.createDirectories(cached.getParent());
                Path temp = cached.resolveSibling(cached.getFileName() + ".tmp");
                SharedWorldClient.apiClient().downloadRawBlobToFile(signedUrl, temp);
                Files.move(temp, cached, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                return cached;
            } catch (Exception exception) {
                SharedWorldClient.LOGGER.warn("Failed to cache Shared World custom icon {}", storageKey, exception);
                return null;
            }
        }, SharedWorldClient.ioExecutor()).whenComplete((ignoredResult, error) -> this.inFlightDownloads.remove(storageKey)));
    }

    private static String sha256(Path path) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = Files.newInputStream(path)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    if (read > 0) {
                        digest.update(buffer, 0, read);
                    }
                }
            }
            StringBuilder builder = new StringBuilder();
            for (byte value : digest.digest()) {
                builder.append(String.format("%02x", value));
            }
            return builder.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IOException("Missing SHA-256 support.", exception);
        }
    }

    public record SelectedIcon(Path path, String hash) {
    }
}

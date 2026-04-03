package link.sharedworld.integration.support;

import com.google.gson.Gson;
import link.sharedworld.api.SharedWorldApiClient;
import link.sharedworld.api.SharedWorldModels;
import org.junit.jupiter.api.Assertions;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;

public final class SharedWorldIntegrationBackend {
    public static final TestPlayer OWNER = new TestPlayer("OwnerO", "00000000-0000-0000-0000-000000000000");
    public static final TestPlayer HOST = new TestPlayer("HostA", "11111111-1111-1111-1111-111111111111");
    public static final TestPlayer GUEST = new TestPlayer("GuestB", "22222222-2222-2222-2222-222222222222");
    public static final TestPlayer ALPHA = new TestPlayer("AlphaC", "33333333-3333-3333-3333-333333333333");
    public static final TestPlayer BRAVO = new TestPlayer("BravoD", "44444444-4444-4444-4444-444444444444");
    private static final String DEV_AUTH_SECRET = "test-dev-auth-secret";
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    private static final Gson GSON = new Gson();

    private SharedWorldIntegrationBackend() {
    }

    public static SharedWorldApiClient apiClient(TestPlayer player) {
        return new SharedWorldApiClient(
                backendUrl(),
                HTTP,
                () -> new SharedWorldApiClient.SessionIdentity(
                        player.playerUuidHyphenated(),
                        player.playerName(),
                        "dev:" + DEV_AUTH_SECRET
                )
        );
    }

    public static void reset() throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(backendUrl() + "/__test/reset"))
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
        HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        Assertions.assertEquals(200, response.statusCode(), response.body());
    }

    public static SharedWorldModels.StorageLinkSessionDto linkStorage(SharedWorldApiClient client) throws IOException, InterruptedException {
        SharedWorldModels.StorageLinkSessionDto pending = client.createStorageLink();
        HttpResponse<String> callbackResponse = HTTP.send(
                HttpRequest.newBuilder().uri(URI.create(pending.authUrl())).GET().build(),
                HttpResponse.BodyHandlers.ofString()
        );
        Assertions.assertEquals(200, callbackResponse.statusCode(), callbackResponse.body());
        SharedWorldModels.StorageLinkSessionDto linked = client.getStorageLink(pending.id());
        Assertions.assertEquals("linked", linked.status());
        return linked;
    }

    public static SessionTestWorld createIdleWorldForSessionTests(String name) throws IOException, InterruptedException {
        SharedWorldApiClient hostClient = apiClient(HOST);
        SharedWorldApiClient guestClient = apiClient(GUEST);
        SharedWorldModels.CreateWorldResultDto created = hostClient.createWorld(
                uniqueName(name),
                null,
                null,
                null,
                null,
                null
        );
        SharedWorldModels.HostAssignmentDto assignment = created.initialUploadAssignment();
        Assertions.assertNotNull(assignment);
        hostClient.releaseHost(created.world().id(), false, assignment.runtimeEpoch(), assignment.hostToken());
        SharedWorldModels.InviteCodeDto invite = hostClient.createInvite(created.world().id());
        guestClient.redeemInvite(invite.code());
        return new SessionTestWorld(created.world(), hostClient, guestClient);
    }

    public static SharedWorldModels.WorldSummaryDto worldSummary(SharedWorldModels.WorldDetailsDto world) {
        return new SharedWorldModels.WorldSummaryDto(
                world.id(),
                world.slug(),
                world.name(),
                world.ownerUuid(),
                world.motd(),
                world.customIconStorageKey(),
                world.customIconDownload(),
                world.memberCount(),
                world.status(),
                world.lastSnapshotId(),
                world.lastSnapshotAt(),
                world.activeHostUuid(),
                world.activeHostPlayerName(),
                world.activeJoinTarget(),
                world.onlinePlayerCount(),
                world.onlinePlayerNames(),
                world.storageProvider(),
                world.storageLinked(),
                world.storageAccountEmail()
        );
    }

    public static StorageSnapshot storageSnapshot() throws IOException, InterruptedException {
        HttpResponse<String> response = HTTP.send(
                HttpRequest.newBuilder().uri(URI.create(backendUrl() + "/__test/storage")).GET().build(),
                HttpResponse.BodyHandlers.ofString()
        );
        Assertions.assertEquals(200, response.statusCode(), response.body());
        return GSON.fromJson(response.body(), StorageSnapshot.class);
    }

    public static String uniqueName(String prefix) {
        return prefix + " " + UUID.randomUUID().toString().substring(0, 8);
    }

    private static String backendUrl() {
        String value = System.getProperty("sharedworld.integration.backendUrl", "").trim();
        if (value.isEmpty()) {
            throw new IllegalStateException("sharedworld.integration.backendUrl is required for integration tests.");
        }
        return value;
    }

    public record TestPlayer(String playerName, String playerUuidHyphenated) {
        public String playerUuid() {
            return this.playerUuidHyphenated.replace("-", "").toLowerCase();
        }
    }

    public record SessionTestWorld(
            SharedWorldModels.WorldDetailsDto world,
            SharedWorldApiClient hostClient,
            SharedWorldApiClient guestClient
    ) {
    }

    public record StorageSnapshot(String provider, StorageObject[] objects) {
    }

    public record StorageObject(String storageKey, String contentType, long size) {
    }
}

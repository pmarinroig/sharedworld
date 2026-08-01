package link.sharedworld.integration;

import link.sharedworld.api.ProfileCertificateData;
import link.sharedworld.api.SharedWorldApiClient;
import link.sharedworld.integration.support.SharedWorldIntegrationBackend;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The one place a Java-signed certificate request meets the real TS verifier:
 * the certificate payload is built here exactly like vanilla
 * ProfilePublicKey.Data#signedPayload builds it (big-endian uuid msb | lsb |
 * expiry millis | SPKI DER) and signed with the integration backend's fake
 * Mojang services key — so a layout disagreement between the Java and TS
 * sides fails this test rather than production logins.
 */
@Tag("integration")
final class BackendModCertAuthIntegrationTest {
    @BeforeEach
    void resetBackend() throws Exception {
        SharedWorldIntegrationBackend.reset();
    }

    @Test
    void certificateAuthMintsARealSessionWithoutTheJoinFlow() throws Exception {
        SharedWorldIntegrationBackend.TestPlayer player = SharedWorldIntegrationBackend.HOST;

        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair profileKeys = generator.generateKeyPair();

        long expiresAtMs = System.currentTimeMillis() + 48L * 60L * 60_000L;
        byte[] publicKeyDer = profileKeys.getPublic().getEncoded();
        byte[] keySignature = signAsMojang(player.playerUuidHyphenated(), expiresAtMs, publicKeyDer);
        ProfileCertificateData certificate = new ProfileCertificateData(
                profileKeys.getPrivate(), publicKeyDer, expiresAtMs, keySignature);

        SharedWorldApiClient client = SharedWorldIntegrationBackend.certApiClient(player, () -> Optional.of(certificate));

        assertEquals(
                player.playerUuidHyphenated().replace("-", ""),
                client.ensureSession().playerUuid(),
                "the minted session belongs to the certified profile");
        assertTrue(client.listWorlds().isEmpty(), "the session token authorizes real API calls");
    }

    /** Mirrors vanilla ProfilePublicKey.Data#signedPayload + Mojang's SHA1withRSA services signature. */
    private static byte[] signAsMojang(String uuidHyphenated, long expiresAtMs, byte[] publicKeyDer) throws Exception {
        UUID uuid = UUID.fromString(uuidHyphenated);
        byte[] payload = new byte[24 + publicKeyDer.length];
        ByteBuffer buffer = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN);
        buffer.putLong(uuid.getMostSignificantBits())
                .putLong(uuid.getLeastSignificantBits())
                .putLong(expiresAtMs)
                .put(publicKeyDer);

        PrivateKey servicesKey = KeyFactory.getInstance("RSA")
                .generatePrivate(new PKCS8EncodedKeySpec(SharedWorldIntegrationBackend.certSigningKeyPkcs8()));
        Signature signature = Signature.getInstance("SHA1withRSA");
        signature.initSign(servicesKey);
        signature.update(payload);
        return signature.sign();
    }
}

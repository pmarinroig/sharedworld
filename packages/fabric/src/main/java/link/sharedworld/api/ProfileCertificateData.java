package link.sharedworld.api;

/**
 * The Mojang-signed profile certificate (1.19+ chat-signing keypair) used for
 * SharedWorld certificate auth: the backend verifies {@code keySignature}
 * (Mojang's SHA1withRSA over uuid + expiry + {@code publicKeyDer}) offline and
 * the client proves possession of {@code privateKey} by signing the challenge
 * nonce. {@code expiresAtEpochMillis} is exactly the value Mojang's signature
 * covers (vanilla signs Instant.toEpochMilli).
 */
public record ProfileCertificateData(
        java.security.PrivateKey privateKey,
        byte[] publicKeyDer,
        long expiresAtEpochMillis,
        byte[] keySignature
) {
}

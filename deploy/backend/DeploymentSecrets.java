import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Properties;
import org.bouncycastle.asn1.gm.GMNamedCurves;

/** Generates deployment credentials once; never needs a running Spring application. */
public class DeploymentSecrets {
    public static void main(String[] args) throws Exception {
        Path directory = Path.of(args[0]);
        Files.createDirectories(directory);
        Path file = directory.resolve("security.properties");
        Properties saved = new Properties();
        if (Files.exists(file)) {
            try (InputStream input = Files.newInputStream(file)) { saved.load(input); }
        }
        Properties effective = new Properties();
        effective.putAll(saved);
        for (String key : new String[]{"SECURITY_SM2_PRIVATEKEY", "SECURITY_SM2_PUBLICKEY", "JWT_BASE64_SECRET_KEY"}) {
            String value = System.getenv(key);
            if (value != null && !value.isBlank()) { effective.setProperty(key, value); }
        }
        var curve = GMNamedCurves.getByName("sm2p256v1");
        SecureRandom random = new SecureRandom();
        String privateHex = effective.getProperty("SECURITY_SM2_PRIVATEKEY");
        BigInteger privateKey;
        if (privateHex == null) {
            if (effective.containsKey("SECURITY_SM2_PUBLICKEY")) {
                throw new IllegalArgumentException("An SM2 public key requires its private key.");
            }
            do { privateKey = new BigInteger(256, random); }
            while (privateKey.signum() == 0 || privateKey.compareTo(curve.getN().subtract(BigInteger.ONE)) >= 0);
        } else {
            privateKey = new BigInteger(privateHex, 16);
            if (privateKey.signum() <= 0 || privateKey.compareTo(curve.getN().subtract(BigInteger.ONE)) >= 0) {
                throw new IllegalArgumentException("Invalid SM2 private key.");
            }
        }
        String publicHex = HexFormat.of().formatHex(curve.getG().multiply(privateKey).normalize().getEncoded(false));
        // An explicit private key can replace a saved pair; an explicit public key must match it.
        String explicitPublic = System.getenv("SECURITY_SM2_PUBLICKEY");
        if (explicitPublic != null && !explicitPublic.isBlank() && !publicHex.equalsIgnoreCase(explicitPublic)) {
            throw new IllegalArgumentException("SM2 public and private keys do not match.");
        }
        effective.setProperty("SECURITY_SM2_PRIVATEKEY", String.format("%064x", privateKey));
        effective.setProperty("SECURITY_SM2_PUBLICKEY", publicHex);
        if (!effective.containsKey("JWT_BASE64_SECRET_KEY")) {
            byte[] secret = new byte[64];
            random.nextBytes(secret);
            effective.setProperty("JWT_BASE64_SECRET_KEY", Base64.getEncoder().encodeToString(secret));
        }
        String jwt = effective.getProperty("JWT_BASE64_SECRET_KEY");
        if (Base64.getDecoder().decode(jwt).length < 64) {
            throw new IllegalArgumentException("JWT secret must contain at least 64 random bytes (Base64 encoded).");
        }
        if (!effective.equals(saved)) {
            Path temporary = directory.resolve("security.properties.tmp");
            try (OutputStream output = Files.newOutputStream(temporary)) {
                effective.store(output, "Deployment secrets. Back up this volume with the database.");
            }
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            System.out.println("Deployment security keys initialized or explicitly updated.");
        }
        StringBuilder environment = new StringBuilder();
        for (String key : new String[]{"SECURITY_SM2_PRIVATEKEY", "SECURITY_SM2_PUBLICKEY", "JWT_BASE64_SECRET_KEY"}) {
            environment.append("export ").append(key).append("='").append(effective.getProperty(key)).append("'\n");
        }
        Files.writeString(Path.of(args[1]), environment);
    }
}

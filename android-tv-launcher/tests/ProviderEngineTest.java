package local.tv.home;

import java.nio.file.*;
import java.security.*;

/** Desktop JVM tests execute the same interpreter and signature verifier used on Android. */
public final class ProviderEngineTest {

    private static int checks;

    private static void check(boolean ok) {
        if (!ok) throw new AssertionError();
        checks++;
    }

    private static void rejected(Attempt action) throws Exception {
        try {
            action.run();
            throw new AssertionError("Accepted invalid input");
        } catch (java.io.IOException | GeneralSecurityException expected) {
            checks++;
        }
    }

    interface Attempt {
        void run() throws Exception;
    }

    public static void main(String[] args) throws Exception {
        String source = Files.readString(Path.of(args[0]));
        check(
            ProviderScript.evaluate(
                source,
                "catalogue",
                "[{\"_id\":\"a\",\"name\":\"Sport\",\"url\":\"https://example.com/a.m3u8\"}]"
            ).contains("Sport")
        );
        check(
            !ProviderScript.evaluate(source, "resolve", "https://example.com/a.m3u8?cb=[CB]").contains("[CB]")
        );
        check(
            ProviderScript.evaluate(
                "function invoke(){return {java:typeof java, packages:typeof Packages, network:typeof fetch};}",
                "",
                ""
            ).equals("{\"java\":\"undefined\",\"packages\":\"undefined\",\"network\":\"undefined\"}")
        );
        rejected(() ->
            ProviderScript.evaluate(
                "function invoke(){return java.lang.Runtime.getRuntime().exec('true');}",
                "",
                ""
            )
        );
        rejected(() ->
            ProviderScript.evaluate(
                "function invoke(){while(true) {try { for(var i=0;i<100000;i++){} } catch(e) {} }}",
                "",
                ""
            )
        );
        rejected(() -> ProviderScript.evaluate(source, "shell", ""));
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(3072);
        KeyPair keys = generator.generateKeyPair();
        byte[] payload = "fixture".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Signature signer = Signature.getInstance("SHA256withRSA");
        signer.initSign(keys.getPrivate());
        signer.update(payload);
        byte[] signature = signer.sign();
        ProviderSignature.verify(payload, signature, keys.getPublic().getEncoded());
        checks++;
        byte[] altered = payload.clone();
        altered[0] ^= 1;
        rejected(() -> ProviderSignature.verify(altered, signature, keys.getPublic().getEncoded()));
        signature[0] ^= 1;
        rejected(() -> ProviderSignature.verify(payload, signature, keys.getPublic().getEncoded()));
        rejected(() ->
            ProviderSignature.verify(
                new byte[ProviderSignature.MAX_PACKAGE + 1],
                signature,
                keys.getPublic().getEncoded()
            )
        );
        generator.initialize(2048);
        KeyPair weak = generator.generateKeyPair();
        rejected(() -> ProviderSignature.verify(payload, signature, weak.getPublic().getEncoded()));
        System.out.println(checks + " provider engine checks passed");
    }
}

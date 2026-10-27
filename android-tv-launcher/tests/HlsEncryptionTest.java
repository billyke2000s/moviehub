package local.tv.home;

import java.security.GeneralSecurityException;
import java.util.*;

public class HlsEncryptionTest {

    private static int checks;

    private static void check(boolean value, String name) {
        checks++;
        if (!value) throw new AssertionError(name);
    }

    private static byte[] hex(String s) {
        byte[] b = new byte[s.length() / 2];
        for (int i = 0; i < b.length; i++) b[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
        return b;
    }

    public static void main(String[] args) throws Exception {
        check(
            HlsEncryption.supported(Map.of("METHOD", "AES-128", "URI", "key")),
            "ordinary identity AES supported"
        );
        check(!HlsEncryption.supported(Map.of("METHOD", "SAMPLE-AES", "URI", "key")), "no sample encryption");
        check(
            !HlsEncryption.supported(
                Map.of("METHOD", "AES-128", "KEYFORMAT", "com.example.protected", "URI", "key")
            ),
            "no protected key formats"
        );
        check(
            Arrays.equals(HlsEncryption.iv("257", null), hex("00000000000000000000000000000101")),
            "sequence number is a big-endian IV"
        );
        check(
            Arrays.equals(HlsEncryption.iv("0", "0x1234"), hex("00000000000000000000000000001234")),
            "explicit hex IV padded on the left"
        );
        // NIST SP 800-38A F.2.1 CBC-AES128 first block; a prefix has no final padding.
        byte[] key = hex("2b7e151628aed2a6abf7158809cf4f3c");
        byte[] decoded = HlsEncryption.decrypt(
            hex("7649abac8119b246cee98e9b12e9197d"),
            16,
            key,
            hex("000102030405060708090a0b0c0d0e0f"),
            false
        );
        check(Arrays.equals(decoded, hex("6bc1bee22e409f96e93d7e117393172a")), "known AES-CBC prefix vector");
        check(Arrays.equals(key, new byte[16]), "caller key material cleared");
        for (String invalid : Arrays.asList("-1", "abc", "999999999999999999999999999999999999999999999")) {
            try {
                HlsEncryption.iv(invalid, null);
                throw new AssertionError("Accepted bad sequence");
            } catch (GeneralSecurityException expected) {
                checks++;
            }
        }
        try {
            HlsEncryption.iv("0", "0xzz");
            throw new AssertionError("Accepted bad IV");
        } catch (GeneralSecurityException expected) {
            checks++;
        }
        try {
            HlsEncryption.decrypt(new byte[15], 15, new byte[16], new byte[16], false);
            throw new AssertionError("Accepted partial AES block");
        } catch (GeneralSecurityException expected) {
            checks++;
        }
        try {
            HlsEncryption.decrypt(new byte[16], 16, new byte[15], new byte[16], false);
            throw new AssertionError("Accepted wrong key size");
        } catch (GeneralSecurityException expected) {
            checks++;
        }
        System.out.println(checks + " HLS encryption checks passed");
    }
}

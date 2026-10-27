package local.tv.home;

import java.math.BigInteger;
import java.security.GeneralSecurityException;
import java.util.Map;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** Standard identity AES-128 HLS prefix handling; protected sample encryption is unsupported. */
final class HlsEncryption {

    static boolean supported(Map<String, String> key) {
        return (
            key.getOrDefault("METHOD", "").equals("AES-128") &&
            key.getOrDefault("KEYFORMAT", "identity").equals("identity") &&
            !key.getOrDefault("URI", "").isEmpty()
        );
    }

    static byte[] iv(String sequence, String explicit) throws GeneralSecurityException {
        try {
            if (
                explicit != null && !explicit.matches("0[xX][0-9a-fA-F]{1,32}")
            ) throw new IllegalArgumentException();
            if (
                explicit == null && (sequence.length() > 39 || !sequence.matches("[0-9]+"))
            ) throw new IllegalArgumentException();
            BigInteger value =
                explicit == null ? new BigInteger(sequence) : new BigInteger(explicit.substring(2), 16);
            if (value.signum() < 0 || value.bitLength() > 128) throw new IllegalArgumentException();
            byte[] raw = value.toByteArray(),
                iv = new byte[16];
            int n = Math.min(raw.length, 16);
            System.arraycopy(raw, raw.length - n, iv, 16 - n, n);
            return iv;
        } catch (IllegalArgumentException error) {
            throw new GeneralSecurityException("Invalid HLS IV");
        }
    }

    static byte[] decrypt(byte[] encoded, int count, byte[] key, byte[] iv, boolean complete)
        throws GeneralSecurityException {
        try {
            if (
                key.length != 16 || iv.length != 16 || count < 16 || count > encoded.length || count % 16 != 0
            ) throw new GeneralSecurityException("Invalid HLS prefix");
            Cipher cipher = Cipher.getInstance(complete ? "AES/CBC/PKCS5Padding" : "AES/CBC/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
            return cipher.doFinal(encoded, 0, count);
        } finally {
            java.util.Arrays.fill(key, (byte) 0);
        }
    }
}

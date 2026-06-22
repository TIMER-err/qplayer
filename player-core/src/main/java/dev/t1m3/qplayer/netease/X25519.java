package dev.t1m3.qplayer.netease;

import java.math.BigInteger;

/**
 * X25519 (Curve25519 Diffie-Hellman) per RFC 7748, implemented with
 * {@link BigInteger} field arithmetic.
 *
 * <p>The platform JCA can't help us here: {@code XDH}/{@code X25519} key
 * agreement only landed in Java 11, but player-core compiles to release 8 and
 * ships to Android API 26 — both predate it. Rather than drag in BouncyCastle
 * (a multi-MB jar, against this module's "no native deps, stay dexable" rule),
 * we do the Montgomery ladder by hand. This is <em>not</em> constant-time, but
 * we use it for one-shot client request encryption where the private scalar is
 * ephemeral and discarded, so side-channel timing is a non-issue.
 *
 * <p>Validated against the RFC 7748 §5.2 test vectors.
 */
final class X25519 {

    private static final BigInteger P =
            BigInteger.valueOf(2).pow(255).subtract(BigInteger.valueOf(19));
    private static final BigInteger A24 = BigInteger.valueOf(121665);
    private static final BigInteger TWO = BigInteger.valueOf(2);

    private X25519() {}

    /** {@code scalar · basepoint(9)} — derive the public key for a private scalar. */
    static byte[] scalarMultBase(byte[] scalar) {
        byte[] u = new byte[32];
        u[0] = 9;
        return scalarMult(scalar, u);
    }

    /** {@code scalar · uPoint}. Both operands are 32-byte little-endian; the
     *  scalar is clamped and the u-coordinate's high bit masked per RFC 7748. */
    static byte[] scalarMult(byte[] scalar, byte[] uBytes) {
        byte[] k = scalar.clone();
        k[0] &= (byte) 248;
        k[31] &= (byte) 127;
        k[31] |= (byte) 64;
        BigInteger kk = leToBig(k);

        byte[] ub = uBytes.clone();
        ub[31] &= (byte) 127;
        BigInteger x1 = leToBig(ub);

        BigInteger x2 = BigInteger.ONE, z2 = BigInteger.ZERO;
        BigInteger x3 = x1, z3 = BigInteger.ONE;
        int swap = 0;

        for (int t = 254; t >= 0; t--) {
            int kt = kk.testBit(t) ? 1 : 0;
            swap ^= kt;
            if (swap == 1) {
                BigInteger tmp;
                tmp = x2; x2 = x3; x3 = tmp;
                tmp = z2; z2 = z3; z3 = tmp;
            }
            swap = kt;

            BigInteger a = x2.add(z2).mod(P);
            BigInteger aa = a.multiply(a).mod(P);
            BigInteger b = x2.subtract(z2).mod(P);
            BigInteger bb = b.multiply(b).mod(P);
            BigInteger e = aa.subtract(bb).mod(P);
            BigInteger c = x3.add(z3).mod(P);
            BigInteger d = x3.subtract(z3).mod(P);
            BigInteger da = d.multiply(a).mod(P);
            BigInteger cb = c.multiply(b).mod(P);

            BigInteger x3n = da.add(cb).mod(P);
            x3 = x3n.multiply(x3n).mod(P);
            BigInteger z3n = da.subtract(cb).mod(P);
            z3 = x1.multiply(z3n.multiply(z3n).mod(P)).mod(P);
            x2 = aa.multiply(bb).mod(P);
            z2 = e.multiply(aa.add(A24.multiply(e).mod(P)).mod(P)).mod(P);
        }

        if (swap == 1) {
            BigInteger tmp;
            tmp = x2; x2 = x3; x3 = tmp;
            tmp = z2; z2 = z3; z3 = tmp;
        }

        BigInteger res = x2.multiply(z2.modPow(P.subtract(TWO), P)).mod(P);
        return bigToLe(res);
    }

    /** 32 little-endian bytes → unsigned BigInteger. */
    private static BigInteger leToBig(byte[] le) {
        byte[] be = new byte[le.length];
        for (int i = 0; i < le.length; i++) be[i] = le[le.length - 1 - i];
        return new BigInteger(1, be);
    }

    /** BigInteger → 32 little-endian bytes (zero-padded / truncated to 32). */
    private static byte[] bigToLe(BigInteger n) {
        byte[] out = new byte[32];
        byte[] be = n.toByteArray(); // big-endian, possibly with sign byte / shorter
        for (int i = 0; i < be.length; i++) {
            int idx = be.length - 1 - i;
            if (i < 32) out[i] = be[idx];
        }
        return out;
    }
}

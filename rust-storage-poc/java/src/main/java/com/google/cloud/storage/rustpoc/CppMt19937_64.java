package com.google.cloud.storage.rustpoc;

/**
 * Reproduces the C++ PoC's random sequence exactly: {@code std::mt19937_64} plus libstdc++'s
 * {@code std::uniform_int_distribution} (Lemire's nearly-divisionless method, used by libstdc++
 * when the generator produces full 64-bit values). With the same seed, {@link #nextIndex(long)}
 * returns the same values as {@code std::uniform_int_distribution<size_t>(0, n - 1)(gen)} in the
 * C++ benchmark, so every language picks the same object sizes and upload types per iteration.
 */
final class CppMt19937_64 {
  private static final int NN = 312;
  private static final int MM = 156;
  private static final long MATRIX_A = 0xB5026F5AA96619E9L;
  private static final long UM = 0xFFFFFFFF80000000L; // most significant 33 bits
  private static final long LM = 0x7FFFFFFFL; // least significant 31 bits

  private final long[] mt = new long[NN];
  private int mti;

  CppMt19937_64(long seed) {
    mt[0] = seed;
    for (mti = 1; mti < NN; mti++) {
      mt[mti] = 6364136223846793005L * (mt[mti - 1] ^ (mt[mti - 1] >>> 62)) + mti;
    }
  }

  /** Next raw 64-bit output, identical to {@code std::mt19937_64::operator()}. */
  long next() {
    if (mti >= NN) {
      int i;
      for (i = 0; i < NN - MM; i++) {
        long x = (mt[i] & UM) | (mt[i + 1] & LM);
        mt[i] = mt[i + MM] ^ (x >>> 1) ^ ((x & 1L) == 0 ? 0 : MATRIX_A);
      }
      for (; i < NN - 1; i++) {
        long x = (mt[i] & UM) | (mt[i + 1] & LM);
        mt[i] = mt[i + (MM - NN)] ^ (x >>> 1) ^ ((x & 1L) == 0 ? 0 : MATRIX_A);
      }
      long x = (mt[NN - 1] & UM) | (mt[0] & LM);
      mt[NN - 1] = mt[MM - 1] ^ (x >>> 1) ^ ((x & 1L) == 0 ? 0 : MATRIX_A);
      mti = 0;
    }
    long x = mt[mti++];
    x ^= (x >>> 29) & 0x5555555555555555L;
    x ^= (x << 17) & 0x71D67FFFEDA60000L;
    x ^= (x << 37) & 0xFFF7EEE000000000L;
    x ^= (x >>> 43);
    return x;
  }

  /** Uniform integer in [0, n), same as libstdc++ {@code uniform_int_distribution(0, n - 1)}. */
  int nextIndex(long n) {
    long product = next();
    long low = product * n; // low 64 bits of the 128-bit product
    long high = Math.unsignedMultiplyHigh(product, n);
    if (Long.compareUnsigned(low, n) < 0) {
      long threshold = Long.remainderUnsigned(-n, n);
      while (Long.compareUnsigned(low, threshold) < 0) {
        product = next();
        low = product * n;
        high = Math.unsignedMultiplyHigh(product, n);
      }
    }
    return (int) high;
  }
}

package org.qubership.integration.platform.camelk.naming.generator;

import java.util.Random;

/*
Inspired by a Kubernetes suffix generation algorithm
(see https://github.com/kubernetes/apimachinery/blob/v0.36.1/pkg/util/rand/rand.go)
 */
public class StringGenerator {
    // Omitting vowels from the set of available characters to reduce the chances
    // of "bad words" being formed.
    private static final String ALPHA_NUMS = "bcdfghjklmnpqrstvwxz2456789";

    // No. of bits required to index into ALPHA_NUMS string.
    private static final int ALPHA_NUMS_IDX_BITS = 5;

    // Mask used to extract last ALPHA_NUMS_IDX_BITS of an int.
    private static final int ALPHA_NUMS_IDX_MASK = (1 << ALPHA_NUMS_IDX_BITS) - 1;

    // No. of random letters we can extract from a single int.
    private static final int MAX_ALPHA_NUMS_PER_LONG = Long.SIZE / ALPHA_NUMS_IDX_BITS;

    private final Random random;

    public StringGenerator(Random random) {
        this.random = random;
    }

    public String generate(int length) {
        StringBuilder sb = new StringBuilder();

        long randomLong = random.nextLong();
        int remaining = MAX_ALPHA_NUMS_PER_LONG;

        for (int i = 0; i < length;) {
            if (remaining == 0) {
                randomLong = random.nextLong();
                remaining = MAX_ALPHA_NUMS_PER_LONG;
            }
            int idx = Long.valueOf(randomLong & ALPHA_NUMS_IDX_MASK).intValue();
            if (idx < ALPHA_NUMS.length()) {
                sb.append(ALPHA_NUMS.charAt(idx));
                i++;
            }
            randomLong >>= ALPHA_NUMS_IDX_BITS;
            remaining--;
        }

        return sb.toString();
    }
}

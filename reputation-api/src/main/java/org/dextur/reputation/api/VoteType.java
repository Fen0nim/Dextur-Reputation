package org.dextur.reputation.api;

public enum VoteType {
    POSITIVE(1), NEGATIVE(-1);
    private final int sign;
    VoteType(int sign) { this.sign = sign; }
    public int sign() { return sign; }
}

package org.dextur.reputation.api;

/** Stable machine-readable rejection. Storage/IO errors instead complete exceptionally with their cause. */
public final class VoteRejectedException extends RuntimeException {
    public enum Code { SELF, UNKNOWN_PLAYER, OFFLINE, PERMISSION, REASON, COMMENT, PLAYTIME,
        ACCOUNT_AGE, DAILY_LIMIT, COOLDOWN, PAIR_COOLDOWN, REPEAT_DISABLED, REVOKE_DISABLED,
        NO_ACTIVE_VOTE, SAME_VOTE, ID_CONFLICT, INVALID_PAGE, TEMPORARILY_RESTRICTED, PROCESSING_BUSY }
    private final Code code;
    private final long remainingSeconds;
    public VoteRejectedException(Code code) { this(code, 0); }
    public VoteRejectedException(Code code, long remainingSeconds) {
        super(code.name()); this.code = code; this.remainingSeconds = remainingSeconds;
    }
    public Code code() { return code; }
    public long remainingSeconds() { return remainingSeconds; }
}

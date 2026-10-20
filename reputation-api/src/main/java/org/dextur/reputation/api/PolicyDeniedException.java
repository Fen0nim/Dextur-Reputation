package org.dextur.reputation.api;

/** An outer policy rejection; ordinary voting safety checks still run after an allow. */
public final class PolicyDeniedException extends RuntimeException {
    private final String reasonKey;
    public PolicyDeniedException(String reasonKey){super(reasonKey);this.reasonKey=reasonKey;}
    public String reasonKey(){return reasonKey;}
}

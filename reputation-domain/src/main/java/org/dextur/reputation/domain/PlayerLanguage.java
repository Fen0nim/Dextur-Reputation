package org.dextur.reputation.domain;

public enum PlayerLanguage {
    ru_RU, en_US;

    public static PlayerLanguage parse(String value) {
        try { return valueOf(value); }
        catch (RuntimeException e) { throw new IllegalArgumentException("Supported languages: ru_RU, en_US"); }
    }
}

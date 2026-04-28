package io.github.tursom.turntf.java;

import org.mindrot.jbcrypt.BCrypt;

public final class PasswordInput {
    public enum Source {
        PLAIN,
        HASHED
    }

    private final Source source;
    private final String encoded;

    private PasswordInput(Source source, String encoded) {
        this.source = source;
        this.encoded = encoded;
    }

    public static PasswordInput plain(String plain) {
        return new PasswordInput(Source.PLAIN, hash(plain));
    }

    public static PasswordInput hashed(String encoded) {
        return new PasswordInput(Source.HASHED, encoded);
    }

    public static String hash(String plain) {
        if (plain == null || plain.isEmpty()) {
            throw new IllegalArgumentException("password is required");
        }
        return BCrypt.hashpw(plain, BCrypt.gensalt());
    }

    public Source source() {
        return source;
    }

    public String encoded() {
        return encoded;
    }

    public void validate() {
        if (source == null) {
            throw new IllegalArgumentException("invalid password source");
        }
        if (encoded == null || encoded.isEmpty()) {
            throw new IllegalArgumentException("password is required");
        }
    }

    public String wireValue() {
        validate();
        return encoded;
    }

    public boolean isHashed() {
        return encoded != null && !encoded.isEmpty();
    }

    public boolean isZero() {
        return source == null && (encoded == null || encoded.isEmpty());
    }
}

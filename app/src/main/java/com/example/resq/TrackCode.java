package com.example.resq;

import java.security.SecureRandom;

/** Makes the short tracking code that is sent inside the SOS SMS. */
public final class TrackCode {

    // no 0, 1, I or O, so the code is easy to read and type
    private static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    public static final int LENGTH = 6;

    private TrackCode() { }

    public static String generate() {
        SecureRandom random = new SecureRandom();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < LENGTH; i++) {
            sb.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        }
        return sb.toString();
    }

    public static boolean isValid(String code) {
        return code != null && code.matches("^[A-Z0-9]{" + LENGTH + "}$");
    }
}

package org.acme.loyalty;

import java.util.regex.Pattern;

public final class Sanitiser {

    private static final Pattern INSTRUCTION_MARKERS = Pattern.compile(
            "(?i)(ignore (all )?(previous|prior|above)|" +
            "system\\s*(message|prompt)|" +
            "you are now|disregard .{0,20}instructions|" +
            "</?(system|assistant|user)>)");

    private static final int MAX_LENGTH = 2000;

    private Sanitiser() {}

    /** Applied to anything retrieved from memory before it reaches a prompt. */
    public static String forPrompt(String text) {
        String truncated = text.length() > MAX_LENGTH ? text.substring(0, MAX_LENGTH) : text;
        return INSTRUCTION_MARKERS.matcher(truncated).replaceAll("[redacted]");
    }

    /** Applied to inbound user messages before they are stored. */
    public static String forStorage(String message) {
        return forPrompt(message.strip());
    }

    public static boolean looksLikeInjection(String text) {
        return INSTRUCTION_MARKERS.matcher(text).find();
    }
}

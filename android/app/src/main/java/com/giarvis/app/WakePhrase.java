package com.giarvis.app;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Shared wake-phrase matching for tap-to-talk and always-on FGS listening.
 * Pragmatic SpeechRecognizer-based detector (Option B). Upgrade path:
 * replace with openWakeWord ONNX once models + Runtime Android AAR are vendored.
 */
public final class WakePhrase {
    public static final String PREF_WAKE_WORD_ENABLED = "wake_word_enabled";

    private static final Pattern WAKE_PREFIX = Pattern.compile(
            "(?i)^(?:ehi|hey|ei|ok|okay)?\\s*j[. ]*a[. ]*r[. ]*v[. ]*i[. ]*s\\b[,:;!?]*\\s*");
    private static final Pattern WAKE_WORD = Pattern.compile(
            "(?i)(?:^|\\s)(?:ehi|hey|ei)?\\s*j[. ]*a[. ]*r[. ]*v[. ]*i[. ]*s\\b");

    private WakePhrase() {}

    public static String normalize(String text) {
        if (text == null) return "";
        return text.toLowerCase(Locale.ROOT).replaceAll("[^a-zàèéìòù0-9 ]", " ").replaceAll("\\s+", " ").trim();
    }

    /** True if the utterance is only the wake word (no command). */
    public static boolean isWakeOnly(String text) {
        String n = normalize(text);
        return n.equals("jarvis") || n.equals("ehi jarvis") || n.equals("hey jarvis")
                || n.equals("ei jarvis") || n.equals("ok jarvis") || n.equals("okay jarvis")
                || n.equals("j a r v i s");
    }

    /** True if wake word appears anywhere as a word. */
    public static boolean containsWake(String text) {
        if (text == null || text.trim().isEmpty()) return false;
        if (isWakeOnly(text)) return true;
        return WAKE_WORD.matcher(text).find();
    }

    /** Strip leading wake phrase; returns remaining command (may be empty). */
    public static String stripWake(String text) {
        if (text == null) return "";
        String trimmed = text.trim();
        Matcher m = WAKE_PREFIX.matcher(trimmed);
        if (m.find()) return trimmed.substring(m.end()).trim();
        if (containsWake(trimmed) && isWakeOnly(trimmed)) return "";
        return trimmed.replaceFirst("(?i)^(?:ehi|hey|ei|ok|okay)?\\s*j[. ]*a[. ]*r[. ]*v[. ]*i[. ]*s(?:[,;:!?]?\\s*)", "").trim();
    }
}
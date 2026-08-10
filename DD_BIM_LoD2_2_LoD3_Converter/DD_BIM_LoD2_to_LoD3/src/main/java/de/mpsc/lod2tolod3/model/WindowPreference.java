package de.mpsc.lod2tolod3.model;

/** Typisierte Sicht auf das WindowPreference-Attribut der Waende ("0"/"1"/"2" im GML). */
public enum WindowPreference {
    NONE,
    NORMAL,
    ABOVE_NEIGHBOR;

    /** Parst den Attribut-String; {@code null} und "0" → {@link #NONE}, "2" → {@link #ABOVE_NEIGHBOR}, sonst {@link #NORMAL}. */
    public static WindowPreference parse(String s) {
        if (s == null || "0".equals(s)) return NONE;
        if ("2".equals(s)) return ABOVE_NEIGHBOR;
        return NORMAL;
    }
}

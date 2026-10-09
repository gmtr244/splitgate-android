package com.projectgamers.splitgate.engine;

/** Ready-made split settings (same position tokens as the Linux version). */
public final class Presets {
    private Presets() {}

    public static final class Preset {
        public final String id;
        public final String label;
        public final String desc;
        final Strategy.Mode mode;
        final String[] positions;
        final int delayMs;
        final boolean oob;
        final boolean disorder;

        Preset(String id, String label, String desc, Strategy.Mode mode, String[] positions, int delayMs, boolean oob) {
            this(id, label, desc, mode, positions, delayMs, oob, false);
        }

        Preset(String id, String label, String desc, Strategy.Mode mode, String[] positions, int delayMs, boolean oob,
               boolean disorder) {
            this.id = id;
            this.label = label;
            this.desc = desc;
            this.mode = mode;
            this.positions = positions;
            this.delayMs = delayMs;
            this.oob = oob;
            this.disorder = disorder;
        }
    }

    public static final Preset NONE = new Preset("none", "DNS only",
            "Encrypted DNS (DoH) only. The ClientHello is not split.",
            Strategy.Mode.OFF, new String[0], 0, false);

    public static final Preset[] ALL = {
            NONE,
            new Preset("hafif", "Light",
                    "A single split in the middle of the domain name.",
                    Strategy.Mode.POSITIONS, new String[]{"midsld"}, 0, false),
            new Preset("sni", "SNI start",
                    "Splits at byte 1 and at the start of the SNI.",
                    Strategy.Mode.POSITIONS, new String[]{"1", "sni"}, 1, false),
            new Preset("agresif", "Aggressive",
                    "Many-piece split (1, 3, SNI start, middle of the domain, SNI end).",
                    Strategy.Mode.POSITIONS, new String[]{"1", "3", "sni", "midsld", "sniend"}, 3, false),
            new Preset("oob", "OOB byte",
                    "Splits at byte 1 and in the middle of the domain name, then sends 1 OOB (urgent) byte after the first piece.",
                    Strategy.Mode.POSITIONS, new String[]{"1", "midsld"}, 2, true),
            new Preset("tlskayit", "TLS + TCP split",
                    "The ClientHello is cut in the middle of the site name into two TLS records, sent as two TCP pieces.",
                    Strategy.Mode.TLS_RECORD, new String[]{"hostmid"}, 3, false),
            new Preset("disorder", "Disorder",
                    "Splits at byte 1; the first piece is sent with TTL 1 so it dies before the DPI and is resent later. The server gets the pieces out of order.",
                    Strategy.Mode.POSITIONS, new String[]{"1"}, 0, false, true),
            new Preset("disordersni", "Disorder (site name)",
                    "Splits in the middle of the domain name and sends the first piece with TTL 1, so the DPI never sees the name in order.",
                    Strategy.Mode.POSITIONS, new String[]{"midsld"}, 0, false, true),
            new Preset("tlsdisorder", "TLS + disorder",
                    "Two TLS records cut in the middle of the site name; the first one is sent with TTL 1.",
                    Strategy.Mode.TLS_RECORD, new String[]{"hostmid"}, 0, false, true),
    };

    public static Preset byId(String id) {
        for (Preset p : ALL) if (p.id.equals(id)) return p;
        for (Preset p : ALL) if (p.id.equals("sni")) return p;
        return NONE;
    }

    public static void apply(Strategy s, Preset p) {
        synchronized (s) {
            s.mode = p.mode;
            s.positions = p.positions;
            s.splitDelayMs = p.delayMs;
            s.oob = p.oob;
            s.disorder = p.disorder;
        }
    }
}

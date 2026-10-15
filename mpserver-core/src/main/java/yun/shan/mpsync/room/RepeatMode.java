package yun.shan.mpsync.room;

import java.util.Locale;

/** Queue behaviour when a track ends. */
public enum RepeatMode {
    /** Stop once the queue drains. */
    OFF,
    /** Replay the current track forever. */
    ONE,
    /** Push finished tracks back to the queue tail, so the room loops the whole list. */
    ALL;

    public String id() { return name().toLowerCase(Locale.ROOT); }

    public static RepeatMode of(String id) {
        if (id == null) return OFF;
        switch (id.trim().toLowerCase(Locale.ROOT)) {
            case "one": case "single": return ONE;
            case "all": case "list":   return ALL;
            default:                   return OFF;
        }
    }
}

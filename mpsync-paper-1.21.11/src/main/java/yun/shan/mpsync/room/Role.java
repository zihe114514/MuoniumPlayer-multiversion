package yun.shan.mpsync.room;

/**
 * Room permission tiers.
 *
 * <p>{@link #ADMIN} is intentionally equal to {@link #OWNER} for every playback and
 * queue action ("管理员有和房主一样的职权"); only room-lifecycle actions — disband,
 * ownership transfer, and demoting the owner — stay owner-exclusive, otherwise an
 * admin could take the room away from its creator.
 */
public enum Role {
    OWNER,
    ADMIN,
    MEMBER;

    public boolean controls() { return this == OWNER || this == ADMIN; }

    public String id() { return name().toLowerCase(java.util.Locale.ROOT); }

    public static Role of(String id) {
        if (id == null) return MEMBER;
        switch (id.trim().toLowerCase(java.util.Locale.ROOT)) {
            case "owner": return OWNER;
            case "admin": return ADMIN;
            default:      return MEMBER;
        }
    }
}

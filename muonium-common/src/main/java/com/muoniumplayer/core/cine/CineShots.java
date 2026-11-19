package com.muoniumplayer.core.cine;

/** Camera composition presets and deterministic selection math. */
public final class CineShots {

    enum Style { ORBIT, PERSON, LANDSCAPE, PAN, SKY, LOW, TOP_DOWN, CAVE, DOLLY_IN, DOLLY_OUT }
    enum Domain { ANY, OVERWORLD, NATURE, FOREST, RIVER, OCEAN, MOUNTAIN, DAY, NIGHT, DAWN, DUSK,
        NETHER, CRIMSON, WARPED, BASALT, SOUL_SAND, END, CAVE, MOB }

    enum Shot {
        ORBIT_WIDE(2.5f, Style.ORBIT, 6.2d, 3.6d, 75d, 0d, 1.44d, 0d, 0f, Domain.ANY),
        ORBIT_CLOSE(-2.5f, Style.ORBIT, 3.6d, 2.1d, 75d, 0d, 1.44d, 0d, 0f, Domain.ANY),
        FRONT_HERO(-1.5f, Style.PERSON, 4.8d, 2.0d, 180d, 0d, 1.05d, 0d, 0f, Domain.ANY),
        REAR_SILHOUETTE(1.5f, Style.PERSON, 6.5d, 3.1d, 0d, 0d, 1.0d, 0d, 0f, Domain.ANY),
        OVER_SHOULDER(-1f, Style.PERSON, 3.4d, 1.72d, 28d, 4d, 1.72d, 0d, 0f, Domain.ANY),
        SIDE_PROFILE(0f, Style.PERSON, 5d, 1.5d, 90d, 0d, 1d, 0d, 0f, Domain.ANY),
        LOW_ANGLE(1f, Style.LOW, 4.8d, 0.42d, 145d, 0d, 1.35d, 0d, 0f, Domain.ANY),
        CRANE_HIGH(1.5f, Style.TOP_DOWN, 4.5d, 8d, 0d, 0d, 0.8d, 0d, 0f, Domain.ANY),
        DOLLY_IN(-3f, Style.DOLLY_IN, 8d, 2.3d, 0d, 0d, 1d, 0d, 0f, Domain.ANY),
        DOLLY_OUT(3f, Style.DOLLY_OUT, 3.5d, 2.3d, 0d, 0d, 1d, 0d, 0f, Domain.ANY),
        DUTCH_SWEEP(0f, Style.PAN, 5.2d, 2.7d, 0d, 16d, 1.1d, 0d, 7f, Domain.ANY),
        PANORAMA(2.5f, Style.PAN, 9.5d, 5.5d, 138d, 18d, 5d, 0d, 42f, Domain.ANY),
        SKY_REVEAL(2f, Style.SKY, 7d, 4.2d, 162d, 18d, 15d, -20d, 18f, Domain.NATURE),
        CAVE_DETAIL(-1.5f, Style.CAVE, 3.6d, 1.4d, 62d, 6d, 4.4d, 105d, 0f, Domain.CAVE),
        MOB_FOLLOW(-2f, Style.ORBIT, 4.2d, 2.4d, 75d, 0d, 0d, 0d, 0f, Domain.MOB),

        PLAYER_HERO_CENTER_LOCK(-1f, Style.PERSON, 6d, 2.4d, 180d, 0d, 1.0d, 0d, 0f, Domain.ANY),
        PLAYER_HERO_CENTER_SWEEP(-0.5f, Style.PAN, 6d, 2.5d, 180d, 0d, 1.0d, 0d, 28f, Domain.ANY),
        PLAYER_REVERSE_ORBIT(1f, Style.ORBIT, 5.2d, 2.8d, -120d, 0d, 1.44d, 0d, 0f, Domain.ANY),
        PLAYER_GROUND_REVEAL(1.5f, Style.LOW, 7.5d, 0.65d, 205d, 18d, 1.3d, 0d, 0f, Domain.NATURE),
        PLAYER_SKY_CRANE(2f, Style.SKY, 6.5d, 9d, 155d, 14d, 10d, -18d, 0f, Domain.NATURE),
        PLAYER_ARC_HIGH(1.5f, Style.ORBIT, 5d, 5.4d, 30d, 0d, 1.44d, 0d, 0f, Domain.ANY),
        PLAYER_ARC_LOW(-0.5f, Style.ORBIT, 5.8d, 0.8d, 220d, 0d, 1.44d, 0d, 0f, Domain.ANY),
        PLAYER_ULTRA_WIDE(3f, Style.PERSON, 12d, 4.3d, 180d, 0d, 0.9d, 0d, 0f, Domain.ANY),
        PLAYER_CLOSEUP_LEFT(-2f, Style.PERSON, 2.8d, 1.8d, 122d, 0d, 1.62d, 0d, 0f, Domain.ANY),
        PLAYER_CLOSEUP_RIGHT(-2f, Style.PERSON, 2.8d, 1.8d, 238d, 0d, 1.62d, 0d, 0f, Domain.ANY),
        PLAYER_CLOSE_CHEST(-1.5f, Style.PERSON, 3.2d, 1.45d, 180d, 0d, 1.05d, 0d, 0f, Domain.ANY),
        PLAYER_CLOSE_PROFILE_LEFT(-1f, Style.PERSON, 2.9d, 1.6d, 90d, 0d, 1.45d, 0d, 0f, Domain.ANY),
        PLAYER_CLOSE_PROFILE_RIGHT(-1f, Style.PERSON, 2.9d, 1.6d, 270d, 0d, 1.45d, 0d, 0f, Domain.ANY),
        PLAYER_CLOSE_OVERHEAD(-1.5f, Style.TOP_DOWN, 3.2d, 5.5d, 35d, 0d, 0.9d, 0d, 0f, Domain.ANY),
        PLAYER_CLOSE_SHOULDER_LEFT(-1f, Style.PERSON, 3.4d, 1.7d, 32d, 4d, 1.5d, 0d, 0f, Domain.ANY),
        PLAYER_CLOSE_SHOULDER_RIGHT(-1f, Style.PERSON, 3.4d, 1.7d, -32d, 4d, 1.5d, 0d, 0f, Domain.ANY),
        PLAYER_CLOSE_FACE_LOW(-2.5f, Style.LOW, 2.6d, 0.65d, 180d, 0d, 1.68d, 0d, 0f, Domain.ANY),
        PLAYER_CINEMA_FRONT_LONG(2f, Style.PERSON, 11d, 2.5d, 180d, 0d, 1.0d, 0d, 0f, Domain.ANY),
        PLAYER_CINEMA_BACK_WIDE(2.5f, Style.PERSON, 10d, 3.4d, 0d, 0d, 1.0d, 0d, 0f, Domain.ANY),
        PLAYER_CINEMA_SIDE_LEFT_WIDE(1.5f, Style.PERSON, 8d, 2.8d, 90d, 0d, 1.0d, 0d, 0f, Domain.ANY),
        PLAYER_CINEMA_SIDE_RIGHT_WIDE(1.5f, Style.PERSON, 8d, 2.8d, 270d, 0d, 1.0d, 0d, 0f, Domain.ANY),
        PLAYER_BACK_RIGHT(0.5f, Style.PERSON, 6d, 2.3d, -42d, 0d, 1.0d, 0d, 0f, Domain.ANY),
        PLAYER_BACK_LEFT(0.5f, Style.PERSON, 6d, 2.3d, 42d, 0d, 1.0d, 0d, 0f, Domain.ANY),
        PLAYER_HEAD_ON(-1f, Style.PERSON, 5.4d, 2.2d, 180d, 0d, 1.2d, 0d, 0f, Domain.ANY),
        PLAYER_SHOULDER_TRACK_RIGHT(-0.5f, Style.PAN, 4.2d, 1.9d, -40d, 4d, 1.3d, 0d, 18f, Domain.ANY),
        PLAYER_SHOULDER_TRACK_LEFT(-0.5f, Style.PAN, 4.2d, 1.9d, 40d, 4d, 1.3d, 0d, -18f, Domain.ANY),
        PLAYER_CINEMA_PORTRAIT_TOP(1f, Style.TOP_DOWN, 4d, 7d, 15d, 0d, 1.0d, 0d, 0f, Domain.NATURE),
        PLAYER_CINEMA_EPIC_DISTANCE(3f, Style.PERSON, 15d, 5d, 180d, 0d, 1.0d, 0d, 0f, Domain.NATURE),

        ENV_WIDE_NATURE(2.5f, Style.LANDSCAPE, 13d, 5d, 180d, 0d, 4d, 0d, 0f, Domain.NATURE),
        ENV_SKY_VIEW(2f, Style.SKY, 8d, 4d, 155d, 20d, 22d, 0d, 0f, Domain.NATURE),
        ENV_HORIZON_SWEEP(2f, Style.PAN, 11d, 4.2d, 180d, 30d, 1.4d, 0d, 52f, Domain.NATURE),
        ENV_TOP_DOWN(1f, Style.TOP_DOWN, 7d, 11d, 35d, 0d, 0.3d, 0d, 0f, Domain.NATURE),
        ENV_TREE_CANOPY(1.5f, Style.SKY, 6d, 5d, 145d, 16d, 13d, -25d, 0f, Domain.FOREST),
        ENV_GROUND_DRIFT(-0.5f, Style.LOW, 8d, 0.55d, 200d, 24d, 1.2d, 0d, 0f, Domain.NATURE),
        ENV_RIDGE_LOOK(2.5f, Style.LANDSCAPE, 12d, 6d, 155d, 28d, 7d, 12d, 0f, Domain.MOUNTAIN),
        ENV_PAN_LEFT(1.5f, Style.PAN, 9d, 3.8d, 180d, 28d, 4d, -28d, -56f, Domain.NATURE),
        ENV_PAN_RIGHT(1.5f, Style.PAN, 9d, 3.8d, 180d, 28d, 4d, 28d, 56f, Domain.NATURE),
        ENV_ESTABLISHING_WIDE(3f, Style.LANDSCAPE, 16d, 6.5d, 180d, 34d, 6d, 0d, 0f, Domain.NATURE),
        ENV_WILDLIFE_WATCH(-1f, Style.ORBIT, 4.8d, 2.5d, 70d, 0d, 0d, 0d, 0f, Domain.MOB),
        NATURE_FOREST_WIDE(2f, Style.LANDSCAPE, 11d, 4.6d, 180d, 27d, 5d, -12d, 0f, Domain.FOREST),
        NATURE_RIVER_VISTA(2.5f, Style.PAN, 10d, 3.5d, 180d, 32d, 2d, 0d, 38f, Domain.RIVER),
        NATURE_MOUNTAIN_CREST(2.5f, Style.LANDSCAPE, 14d, 7d, 180d, 38d, 9d, 0d, 0f, Domain.MOUNTAIN),
        NATURE_PLAINS_PAN(2f, Style.PAN, 10d, 3.8d, 180d, 32d, 2.2d, 0d, 48f, Domain.OVERWORLD),
        NATURE_OCEAN_HORIZON(2.5f, Style.PAN, 11d, 3.8d, 180d, 34d, 1.2d, 0d, 44f, Domain.OCEAN),
        NATURE_BEACH_REVEAL(1.5f, Style.LANDSCAPE, 8d, 3.2d, 180d, 25d, 2.2d, 18d, 0f, Domain.OCEAN),
        SUNRISE_HORIZON_GLOW(2f, Style.PAN, 10d, 3.5d, 180d, 32d, 1.4d, 0d, 34f, Domain.DAWN),
        SUNSET_GOLDEN_RIM(2f, Style.PAN, 10d, 3.5d, 180d, 32d, 1.4d, 0d, -34f, Domain.DUSK),
        NIGHT_MOONLINE(1.5f, Style.SKY, 8d, 4.5d, 150d, 26d, 24d, 0d, 0f, Domain.NIGHT),
        NIGHT_STARFIELD_WIDE(2.5f, Style.SKY, 12d, 5.5d, 170d, 32d, 28d, 0d, 0f, Domain.NIGHT),
        NETHER_LAVA_RIDGE(2.5f, Style.LANDSCAPE, 11d, 5d, 180d, 28d, 5d, 0d, 0f, Domain.NETHER),
        NETHER_BASALT_CANYON(1.5f, Style.PAN, 10d, 4d, 180d, 27d, 2d, 0d, 36f, Domain.BASALT),
        NETHER_CRIMSON_SWEEP(2f, Style.PAN, 9d, 4d, 180d, 25d, 4d, 0d, 42f, Domain.CRIMSON),
        NETHER_WARPED_REVEAL(2f, Style.LANDSCAPE, 10d, 5d, 180d, 26d, 6d, 18d, 0f, Domain.WARPED),
        NETHER_SOUL_VALLEY(2.5f, Style.LANDSCAPE, 13d, 5.5d, 180d, 34d, 6d, 0d, 0f, Domain.SOUL_SAND),
        END_SPIRE_WIDE(2.5f, Style.LANDSCAPE, 12d, 5d, 180d, 30d, 10d, 0d, 0f, Domain.END),
        END_VOID_EDGE(1.5f, Style.PAN, 9d, 3.4d, 180d, 30d, 0d, 0d, 42f, Domain.END),
        END_OBSIDIAN_RING(2f, Style.PAN, 13d, 4.2d, 180d, 28d, 2d, 0d, -45f, Domain.END),
        CAVE_CHAMBER_WIDE(1f, Style.CAVE, 7d, 3.2d, 180d, 14d, 4d, 0d, 0f, Domain.CAVE),
        CAVE_PASSAGE_TRACK(0.5f, Style.PAN, 4.5d, 1.6d, 180d, 15d, 1.5d, 0d, 22f, Domain.CAVE),
        CAVE_WALL_DETAIL(-1.5f, Style.CAVE, 3d, 1.5d, 70d, 7d, 1.6d, 95d, 0f, Domain.CAVE),
        CAVE_FLOOR_DRIFT(0f, Style.LOW, 4d, 0.35d, 180d, 10d, 0.2d, 0d, 0f, Domain.CAVE),
        CAVE_CEILING_ARC(1f, Style.TOP_DOWN, 4.2d, 6.5d, 30d, 7d, 3.5d, 0d, 0f, Domain.CAVE),
        CAVE_GLOW_POCKET(-1f, Style.CAVE, 3.5d, 1.8d, 125d, 8d, 2.2d, -90d, 0f, Domain.CAVE),
        CAVE_DEPTH_REVEAL(1.5f, Style.PAN, 6d, 2.5d, 180d, 18d, 3d, 0d, 30f, Domain.CAVE);

        final float fovOffset;
        final float rollDegrees;
        final Style style;
        final Domain domain;
        final double radius;
        final double cameraHeight;
        final double cameraAngle;
        final double focusDistance;
        final double focusHeight;
        final double focusAngle;
        final double sweepDegrees;

        Shot(float fovOffset, Style style, double radius, double cameraHeight, double cameraAngle,
             double focusDistance, double focusHeight, double focusAngle, float rollDegrees, Domain domain) {
            this.fovOffset = fovOffset;
            this.rollDegrees = 0f;
            this.style = style;
            this.domain = domain;
            this.radius = radius;
            this.cameraHeight = cameraHeight;
            this.cameraAngle = cameraAngle;
            this.focusDistance = focusDistance;
            this.focusHeight = focusHeight;
            this.focusAngle = focusAngle;
            this.sweepDegrees = style == Style.PAN ? focusAngle : 0d;
        }
    }

    /** No-repeat deck that exhausts scene-eligible compositions before reshuffling. */
    static final class ShotDeck {
        private final boolean[] used = new boolean[Shot.values().length];
        private long state;
        private Shot previous;

        ShotDeck(long seed) {
            state = seed == 0L ? 0x9E3779B97F4A7C15L : seed;
        }

        Shot next(boolean skyVisible, boolean mobAvailable, boolean preferMob) {
            return next(0, "plains", 6000L, skyVisible, mobAvailable, preferMob);
        }

        /** 生物通道独立于环境/人物配额；多数跟随生物，偶尔环绕野生动物观察。 */
        Shot takeMobIfAvailable() {
            state ^= state << 13;
            state ^= state >>> 7;
            state ^= state << 17;
            if (Math.floorMod(state, 3L) == 0L) {
                Shot wildlife = takeMobShot(Shot.ENV_WILDLIFE_WATCH);
                if (wildlife != null) return wildlife;
            }
            Shot follow = takeMobShot(Shot.MOB_FOLLOW);
            return follow != null ? follow : takeMobShot(Shot.ENV_WILDLIFE_WATCH);
        }

        private Shot takeMobShot(Shot candidate) {
            if (used[candidate.ordinal()] || previous == candidate) return null;
            used[candidate.ordinal()] = true;
            previous = candidate;
            return candidate;
        }

        Shot next(int dimension, String biomeKey, long timeOfDay, boolean skyVisible,
                  boolean mobAvailable, boolean preferMob) {
            return nextMatching(dimension, biomeKey, timeOfDay, skyVisible, mobAvailable, preferMob, false, false);
        }

        Shot nextPlayer(int dimension, String biomeKey, long timeOfDay, boolean skyVisible) {
            return nextMatching(dimension, biomeKey, timeOfDay, skyVisible, false, false, true, false);
        }

        Shot nextEnvironment(int dimension, String biomeKey, long timeOfDay, boolean skyVisible) {
            return nextMatching(dimension, biomeKey, timeOfDay, skyVisible, false, false, false, true);
        }

        Shot nextAny(int dimension, String biomeKey, long timeOfDay, boolean skyVisible,
                     boolean mobAvailable, boolean preferMob) {
            return nextMatching(dimension, biomeKey, timeOfDay, skyVisible,
                    mobAvailable, preferMob, false, false);
        }

        private Shot nextMatching(int dimension, String biomeKey, long timeOfDay, boolean skyVisible,
                                  boolean mobAvailable, boolean preferMob, boolean playerOnly,
                                  boolean environmentOnly) {
            if (preferMob && mobAvailable && !playerOnly && !environmentOnly
                    && !used[Shot.MOB_FOLLOW.ordinal()] && previous != Shot.MOB_FOLLOW) {
                used[Shot.MOB_FOLLOW.ordinal()] = true;
                previous = Shot.MOB_FOLLOW;
                return previous;
            }
            int available = countAvailable(dimension, biomeKey, timeOfDay, skyVisible,
                    mobAvailable, playerOnly, environmentOnly, true);
            if (available == 0) {
                for (int i = 0; i < used.length; i++) used[i] = false;
                available = countAvailable(dimension, biomeKey, timeOfDay, skyVisible,
                        mobAvailable, playerOnly, environmentOnly, true);
            }
            if (available == 0) available = countAvailable(dimension, biomeKey, timeOfDay, skyVisible,
                    mobAvailable, playerOnly, environmentOnly, false);
            if (available == 0) return null;

            state ^= state << 13;
            state ^= state >>> 7;
            state ^= state << 17;
            int choice = (int) Math.floorMod(state, available);
            for (Shot shot : Shot.values()) {
                int index = shot.ordinal();
                if (!eligible(shot, dimension, biomeKey, timeOfDay, skyVisible,
                        mobAvailable, playerOnly, environmentOnly) || used[index] || shot == previous) continue;
                if (choice-- == 0) {
                    used[index] = true;
                    previous = shot;
                    return shot;
                }
            }
            throw new IllegalStateException("镜头牌组状态不一致");
        }

        private int countAvailable(int dimension, String biomeKey, long timeOfDay, boolean skyVisible,
                                   boolean mobAvailable, boolean playerOnly, boolean environmentOnly,
                                   boolean unusedOnly) {
            int count = 0;
            for (Shot shot : Shot.values()) {
                if (eligible(shot, dimension, biomeKey, timeOfDay, skyVisible,
                        mobAvailable, playerOnly, environmentOnly)
                        && (!unusedOnly || !used[shot.ordinal()]) && shot != previous) count++;
            }
            return count;
        }

        private static boolean eligible(Shot shot, int dimension, String biomeKey, long timeOfDay,
                                        boolean skyVisible, boolean mobAvailable, boolean playerOnly,
                                        boolean environmentOnly) {
            boolean environment = isEnvironment(shot);
            if (playerOnly && (environment || shot.domain == Domain.MOB)) return false;
            if (environmentOnly && !environment) return false;
            if (shot.domain == Domain.MOB) return mobAvailable;
            if (shot.domain == Domain.CAVE) return !skyVisible;
            if (shot.domain == Domain.NATURE && (dimension != 0 || !skyVisible)) return false;
            if (shot.domain == Domain.OVERWORLD && (dimension != 0 || !skyVisible)) return false;
            if (shot.domain == Domain.FOREST && (dimension != 0 || !containsAny(biomeKey, "forest", "jungle", "taiga"))) return false;
            if (shot.domain == Domain.RIVER && (dimension != 0 || !containsAny(biomeKey, "river"))) return false;
            if (shot.domain == Domain.OCEAN && (dimension != 0 || !containsAny(biomeKey, "ocean", "beach"))) return false;
            if (shot.domain == Domain.MOUNTAIN && (dimension != 0 || !containsAny(biomeKey, "mountain", "peak", "slope", "meadow"))) return false;
            if (shot.domain == Domain.NETHER && dimension != 1) return false;
            if (shot.domain == Domain.CRIMSON && (dimension != 1 || !containsAny(biomeKey, "crimson"))) return false;
            if (shot.domain == Domain.WARPED && (dimension != 1 || !containsAny(biomeKey, "warped"))) return false;
            if (shot.domain == Domain.BASALT && (dimension != 1 || !containsAny(biomeKey, "basalt"))) return false;
            if (shot.domain == Domain.SOUL_SAND && (dimension != 1 || !containsAny(biomeKey, "soul_sand"))) return false;
            if (shot.domain == Domain.END && dimension != 2) return false;
            if ((shot.domain == Domain.DAY || shot.domain == Domain.NIGHT || shot.domain == Domain.DAWN
                    || shot.domain == Domain.DUSK) && dimension != 0) return false;
            if (shot.domain == Domain.DAY && !isDay(timeOfDay)) return false;
            if (shot.domain == Domain.NIGHT && !isNight(timeOfDay)) return false;
            if (shot.domain == Domain.DAWN && !isDawn(timeOfDay)) return false;
            if (shot.domain == Domain.DUSK && !isDusk(timeOfDay)) return false;
            if (shot == Shot.SKY_REVEAL && !skyVisible) return false;
            if (shot == Shot.CAVE_DETAIL && skyVisible) return false;
            return true;
        }

        static boolean isEnvironment(Shot shot) {
            String name = shot.name();
            return name.startsWith("ENV_") || name.startsWith("NATURE_")
                    || name.startsWith("SUNRISE_") || name.startsWith("SUNSET_")
                    || name.startsWith("NIGHT_") || name.startsWith("NETHER_")
                    || name.startsWith("END_") || name.startsWith("CAVE_")
                    || shot == Shot.PANORAMA || shot == Shot.SKY_REVEAL || shot == Shot.CAVE_DETAIL;
        }

        private static boolean containsAny(String value, String... fragments) {
            if (value == null) return false;
            String normalized = value.toLowerCase(java.util.Locale.ROOT);
            for (String fragment : fragments) if (normalized.contains(fragment)) return true;
            return false;
        }

        private static boolean isDay(long time) { return time >= 5000L && time < 13000L; }
        private static boolean isNight(long time) { return time >= 13000L || time < 5000L; }
        // DAWN/DUSK 仅 1.20.1/1.21.1/1.21.10 的精确时钟可命中；26.x 与 1.21.11 探针
        // 只有天光二值口径，这两个域在这四树为显式降级、镜头不可达。
        private static boolean isDawn(long time) { return time >= 0L && time < 3000L; }
        private static boolean isDusk(long time) { return time >= 21000L && time < 24000L; }
    }

    private CineShots() { }

    /** Quintic easing with zero velocity at both ends. */
    public static double smootherstep(double t) {
        if (!(t > 0d)) return 0d;
        if (t > 1d) return 1d;
        return t * t * t * (t * (t * 6d - 15d) + 10d);
    }

    /** Normalize an angle difference to [-180, 180). */
    public static float wrapDegrees(float deg) {
        float wrapped = deg % 360f;
        if (wrapped >= 180f) wrapped -= 360f;
        if (wrapped < -180f) wrapped += 360f;
        return wrapped;
    }

    /** Deterministic splitmix64 sample in [min, max). */
    static double randRange(long seed, int salt, double min, double max) {
        long mixed = seed + salt * -7046029254386353131L;
        mixed ^= mixed >>> 30;
        mixed *= -49064778989728563L;
        mixed ^= mixed >>> 27;
        mixed *= -4265267296055464877L;
        mixed ^= mixed >>> 31;
        double unit = (mixed & 0x1FFFFFFFFFFFFFL) / (double) (1L << 53);
        return min + (max - min) * unit;
    }
}

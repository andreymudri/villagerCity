package dev.andreymudri.villagercity.village.plot;

/** Cut and fill for levelling columns to one floor height. A column's groundY is its first free y ({@link Column#groundY}). */
public final class Earthwork {
    /** Most any one column of a spot may lie above or below its floor and still be levelled. */
    public static final int MAX_COLUMN_STEP = 6;
    /** Most blocks a spot may need cut plus filled and still be levelled. */
    public static final int MAX_VOLUME = 80;
    /**
     * Most a column may lie below a floor and still need no earthwork: the house's own floor spans a one-block dip, so a
     * builder lays it with no paver. This is what a village without a paver may build on.
     */
    public static final int FLOOR_SPAN = 1;

    /** A floor height (the first free y above the levelled ground) and the blocks to cut plus fill to reach it. */
    public record Level(int floorY, int volume) {
    }

    private Earthwork() {
    }

    /** The floor y between the lowest and highest groundY with the least cut + fill; ties go to the higher floor. */
    public static Level best(int[] groundYs) {
        if (groundYs.length == 0) {
            throw new IllegalArgumentException("no columns");
        }
        int low = Integer.MAX_VALUE;
        int high = Integer.MIN_VALUE;
        for (int groundY : groundYs) {
            low = Math.min(low, groundY);
            high = Math.max(high, groundY);
        }
        Level best = null;
        for (int floorY = high; floorY >= low; floorY--) {
            int volume = volume(groundYs, floorY);
            if (best == null || volume < best.volume()) {
                best = new Level(floorY, volume);
            }
        }
        return best;
    }

    /** Blocks to cut (groundY - floorY when positive) plus blocks to fill (floorY - groundY when positive), summed over columns. */
    public static int volume(int[] groundYs, int floorY) {
        int volume = 0;
        for (int groundY : groundYs) {
            volume += Math.abs(groundY - floorY);
        }
        return volume;
    }

    /** True when no column lies above {@code floorY} or more than {@link #FLOOR_SPAN} below it, so levelling takes no earthwork. */
    public static boolean needsNone(int[] groundYs, int floorY) {
        for (int groundY : groundYs) {
            if (groundY > floorY || groundY < floorY - FLOOR_SPAN) {
                return false;
            }
        }
        return true;
    }

    /** How far the column farthest from {@code floorY} lies above or below it; 0 for no columns. */
    public static int maxColumnStep(int[] groundYs, int floorY) {
        int step = 0;
        for (int groundY : groundYs) {
            step = Math.max(step, Math.abs(groundY - floorY));
        }
        return step;
    }
}

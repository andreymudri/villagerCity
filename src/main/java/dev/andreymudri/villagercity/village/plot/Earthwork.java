package dev.andreymudri.villagercity.village.plot;

/** Cut and fill for levelling columns to one floor height. A column's groundY is its first free y ({@link Column#groundY}). */
public final class Earthwork {
    /** Most a spot's ground may vary in height and still be levelled. */
    public static final int MAX_VARIANCE = 6;
    /** Most blocks a spot may need cut plus filled and still be levelled. */
    public static final int MAX_VOLUME = 80;

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
}

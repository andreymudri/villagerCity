package dev.andreymudri.villagercity.village.plot;

/** One sampled ground column: the first free y above the ground, whether the ground is natural, whether it is fluid. */
public record Column(int groundY, boolean natural, boolean fluid) {
    public static final Column MISSING = new Column(Integer.MIN_VALUE, false, false);

    public boolean present() {
        return groundY != Integer.MIN_VALUE;
    }
}

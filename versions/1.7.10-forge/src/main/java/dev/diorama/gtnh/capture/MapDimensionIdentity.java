package dev.diorama.gtnh.capture;

/** Game-independent dimension policy for the legacy sixteen-section snapshot format. */
public final class MapDimensionIdentity {

    private MapDimensionIdentity() {}

    public static boolean supportsHeight(int height) {
        return height == 256;
    }

    public static boolean surface(int id, boolean noSky, boolean endProvider) {
        return !noSky || (id == 1 && endProvider);
    }

    public static String suffix(int id, boolean noSky, boolean endProvider) {
        if (id == 0 && !noSky) return "overworld";
        if (id == -1 && noSky) return "nether";
        if (id == 1 && noSky && endProvider) return "end";
        return "dim-" + id + (surface(id, noSky, endProvider) ? "-surface" : "-cave");
    }

    public static boolean validSuffix(String suffix) {
        if ("overworld".equals(suffix) || "nether".equals(suffix) || "end".equals(suffix)) return true;
        if (suffix == null || !suffix.matches("dim--?[0-9]{1,10}-(surface|cave)")) return false;
        int end = suffix.lastIndexOf('-');
        try {
            int id = Integer.parseInt(suffix.substring(4, end));
            return suffix.equals("dim-" + id + suffix.substring(end));
        } catch (NumberFormatException invalid) {
            return false;
        }
    }
}

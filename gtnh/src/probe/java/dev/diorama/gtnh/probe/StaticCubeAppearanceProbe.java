package dev.diorama.gtnh.probe;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.function.BooleanSupplier;

import dev.diorama.bake.Looks;
import dev.diorama.bake.PreparedBlockLooks;
import dev.diorama.gtnh.capture.StaticCubeAppearance;

/** Standalone CPU contracts against production helpers; no substitute Minecraft classes. */
public final class StaticCubeAppearanceProbe {

    private static final float[] TOP = { 1, 0.5F, 0, 1 };
    private static final float[] SIDE = { 0, 0.25F, 1, 1 };
    private static final String[] ALIASES = { "mcp", "srg" };
    private static int assertions;
    private static MessageDigest digest;

    private StaticCubeAppearanceProbe() {}

    public static void main(String[] args) throws Exception {
        digest = MessageDigest.getInstance("SHA-256");
        success();
        currentBoundaries();
        failures();
        reentrantInvalidation();
        inheritance();
        bounds();
        StringBuilder hex = new StringBuilder();
        for (byte value : digest.digest()) {
            hex.append(Character.forDigit((value >>> 4) & 15, 16));
            hex.append(Character.forDigit(value & 15, 16));
        }
        System.out.println("FIXTURE-SHA256=" + hex);
        System.out.println(
            "PASS static cube appearance; assertions=" + assertions
                + "; callback fences, atomic rejection, reentrant invalidate, reflection inheritance, exact unit bounds"
                + "; MC/GL/resource integration UNTESTED");
    }

    private static PreparedBlockLooks palette() {
        return new PreparedBlockLooks(new Object(), 3);
    }

    private static void success() {
        PreparedBlockLooks looks = palette();
        Callbacks access = new Callbacks();
        int id = StaticCubeAppearance.prepare(looks, 42, 7, access, access);
        require(id == 1 && looks.find(42, 7) == id, "success publishes dense ID and key");
        require(
            access.trace.toString()
                .equals("CECTCSCC"),
            "current before/after eligible and averages, plus finally");
        require(looks.isPrepared(id) && looks.kind[id] == Looks.SOLID, "success release-publishes solid");
        require(looks.top[id] == 0xFF8000 && looks.side[id] == 0x0040FF, "top and side remain distinct");
        require(
            looks.emission[id] == 0 && looks.tint[id] == Looks.TINT_NONE,
            "static cube has no inferred light or tint");
        require(
            looks.constantTint[id] == -1 && looks.topTintWeight[id] == 0 && looks.sideTintWeight[id] == 0,
            "static cube colours untinted");
        put(looks.top[id] + ":" + looks.side[id] + ":" + access.trace);
        require(
            StaticCubeAppearance.prepare(looks, 42, 7, new Callbacks(), () -> true) == id,
            "repeat success preserves ID");
    }

    private static void currentBoundaries() {
        String[] traces = { "CC", "CECC", "CECTCC", "CECTCSCC", "CECTCSCC" };
        for (int cutoff = 1; cutoff <= 5; cutoff++) {
            PreparedBlockLooks looks = palette();
            Callbacks access = new Callbacks();
            access.cutoff = cutoff;
            int result = StaticCubeAppearance.prepare(looks, 42, 7, access, access);
            require(result == -1, "current false at boundary " + cutoff);
            require(
                access.trace.toString()
                    .equals(traces[cutoff - 1]),
                "no callbacks beyond fence " + cutoff);
            require(access.currentCalls == (cutoff < 5 ? cutoff + 1 : 5), "finally always checks current " + cutoff);
            rejectedWithoutAllocation(looks, "current boundary " + cutoff);
        }
        PreparedBlockLooks looks = palette();
        Callbacks access = new Callbacks();
        access.eligible = false;
        require(StaticCubeAppearance.prepare(looks, 42, 7, access, access) == -1, "ineligible rejected");
        require(
            access.trace.toString()
                .equals("CEC"),
            "ineligible skips both averages, runs finally");
        rejectedWithoutAllocation(looks, "ineligible");
    }

    private static void failures() {
        // Stage 0 is eligible, stage 1 top, stage 2 side. Null is an unsupported average.
        for (int stage = 0; stage <= 2; stage++) {
            for (int failure = 0; failure <= 3; failure++) {
                if (stage == 0 && failure < 2) continue;
                PreparedBlockLooks looks = palette();
                Callbacks access = new Callbacks();
                access.failureStage = stage;
                access.failure = failure;
                String label = "failure stage=" + stage + " kind=" + failure;
                require(StaticCubeAppearance.prepare(looks, 42, 7, access, access) == -1, label + " returns unknown");
                String expected = stage == 0 ? "CEC"
                    : stage == 1 ? (failure == 0 ? "CECTCC" : "CECTC") : (failure == 0 ? "CECTCSCC" : "CECTCSC");
                require(
                    access.trace.toString()
                        .equals(expected),
                    label + " callback order and finally");
                rejectedWithoutAllocation(looks, label);
            }
        }
    }

    private static void rejectedWithoutAllocation(PreparedBlockLooks looks, String label) {
        require(looks.find(42, 7) == -1, label + " no registered key");
        require(!looks.isPrepared(1) && looks.kind[1] == -1, label + " no published fallback");
        require(looks.top[1] == 0 && looks.side[1] == 0, label + " no partial face payload");
        require(
            StaticCubeAppearance.prepare(looks, 43, 0, new Callbacks(), () -> true) == 1,
            label + " did not consume first ID");
        require(
            StaticCubeAppearance.prepare(looks, 42, 7, new Callbacks(), () -> true) == 2,
            label + " failed key can retry at next dense ID");
    }

    private static void reentrantInvalidation() {
        for (int stage = 0; stage <= 2; stage++) {
            for (boolean fenceTracksInvalidation : new boolean[] { false, true }) {
                PreparedBlockLooks looks = palette();
                int old = looks.cube(9, 0, TOP, SIDE, 0);
                Callbacks access = new Callbacks();
                access.invalidateStage = stage;
                access.invalidateLooks = looks;
                BooleanSupplier current = fenceTracksInvalidation ? access : () -> true;
                String label = "reentrant stage=" + stage + " tracked=" + fenceTracksInvalidation;
                require(StaticCubeAppearance.prepare(looks, 42, 7, access, current) == -1, label + " rejected");
                require(looks.find(42, 7) == -1 && looks.find(9, 0) == -1, label + " invalidated lookup");
                require(
                    !looks.isPrepared(2) && looks.kind[2] == -1 && looks.top[2] == 0 && looks.side[2] == 0,
                    label + " no publication after reentry");
                require(
                    looks.isPrepared(old) && looks.top[old] == 0xFF8000 && looks.side[old] == 0x0040FF,
                    label + " in-flight old payload immutable");
                require(looks.cube(43, 0, TOP, SIDE, 0) == -1, label + " invalidation irreversible");
                if (fenceTracksInvalidation) {
                    String expected = stage == 0 ? "CECC" : stage == 1 ? "CECTCC" : "CECTCSCC";
                    require(
                        access.trace.toString()
                            .equals(expected),
                        label + " stops after invalidating callback");
                }
            }
        }
    }

    private static void inheritance() {
        require(!inherits(NoAliasChild.class, NoAlias.class, ALIASES), "no alias declared by base fails closed");
        require(
            !inherits(Impostor.class, NoAlias.class, ALIASES),
            "unrelated alias names cannot establish inheritance");
        require(inherits(DefaultChild.class, Defaults.class, ALIASES), "inherits defaults without override");
        require(inherits(Defaults.class, Defaults.class, ALIASES), "base itself owns defaults");
        require(
            inherits(DefaultChild.class, Defaults.class, new String[] { "absent", "mcp", "absent2" }),
            "absent aliases ignored when actual base alias exists");
        require(!inherits(DefaultChild.class, Defaults.class, new String[0]), "empty aliases fail closed");
        require(!inherits(Grandchild.class, Defaults.class, ALIASES), "intermediate parent override rejected");
        require(!inherits(OverrideMcp.class, Defaults.class, ALIASES), "first alias override rejected");
        require(
            !inherits(OverrideSrg.class, Defaults.class, ALIASES),
            "second alias override rejected despite default first");
        require(
            !inherits(OverrideSrg.class, Defaults.class, new String[] { "srg", "mcp" }),
            "alias order cannot hide override");
        require(
            inherits(Overload.class, Defaults.class, ALIASES),
            "different signature does not override no-arg alias");
        require(
            !inherits(Overload.class, Defaults.class, ALIASES, int.class),
            "matching int signature override rejected");
        require(inherits(DefaultChild.class, Defaults.class, ALIASES, int.class), "inherited int signature accepted");
        require(
            !inherits(Overload.class, Defaults.class, ALIASES, String.class),
            "subclass-only signature not base alias");
        require(
            !inherits(DefaultChild.class, Defaults.class, ALIASES, Integer.class),
            "boxed signature differs from primitive");
    }

    private static boolean inherits(Class<?> type, Class<?> base, String[] aliases, Class<?>... parameters) {
        return StaticCubeAppearance.inherits(type, base, aliases, parameters);
    }

    // Ordinary reflection fixtures, not Minecraft stand-ins or geometry classifiers.
    public static class NoAlias {
    }

    public static class NoAliasChild extends NoAlias {

        public void mcp() {}
    }

    public static class Impostor {

        public void mcp() {}

        public void srg() {}
    }

    public static class Defaults {

        public void mcp() {}

        public void srg() {}

        public void mcp(int value) {}
    }

    public static class DefaultChild extends Defaults {
    }

    public static class OverrideMcp extends Defaults {

        @Override
        public void mcp() {}
    }

    public static class OverrideSrg extends Defaults {

        @Override
        public void srg() {}
    }

    public static class Grandchild extends OverrideMcp {
    }

    public static class Overload extends Defaults {

        @Override
        public void mcp(int value) {}

        public void srg(String value) {}
    }

    private static void bounds() {
        require(unit(new double[] { 0, 0, 0, 1, 1, 1 }), "exact unit bounds accepted");
        require(unit(new double[] { -0.0, 0, -0.0, 1, 1, 1 }), "signed zero denotes same exact bound");
        for (int axis = 0; axis < 6; axis++) {
            double expected = axis < 3 ? 0 : 1;
            double[] invalid = { Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, Math.nextUp(expected),
                Math.nextDown(expected), expected + 1.0e-7, expected - 1.0e-7 };
            for (double value : invalid) {
                double[] box = { 0, 0, 0, 1, 1, 1 };
                box[axis] = value;
                require(
                    !unit(box),
                    "reject non-unit axis=" + axis + " bits=" + Long.toHexString(Double.doubleToLongBits(value)));
            }
        }
        for (int axis = 0; axis < 3; axis++) {
            double[] reversed = { 0, 0, 0, 1, 1, 1 };
            reversed[axis] = 1;
            reversed[axis + 3] = 0;
            require(!unit(reversed), "inverted bounds rejected axis=" + axis);
        }
    }

    private static boolean unit(double[] b) {
        return StaticCubeAppearance.unitBounds(b[0], b[1], b[2], b[3], b[4], b[5]);
    }

    private static final class Callbacks implements StaticCubeAppearance.Access, BooleanSupplier {

        final StringBuilder trace = new StringBuilder();
        int currentCalls;
        int cutoff = Integer.MAX_VALUE;
        boolean eligible = true;
        boolean live = true;
        int failureStage = -1;
        int failure;
        int invalidateStage = -1;
        PreparedBlockLooks invalidateLooks;

        @Override
        public boolean getAsBoolean() {
            trace.append('C');
            return ++currentCalls < cutoff && live;
        }

        @Override
        public boolean eligible() {
            trace.append('E');
            invalidate(0);
            if (failureStage == 0) uncheckedFailure();
            return eligible;
        }

        @Override
        public float[] average(int side) throws IOException {
            require(side == 1 || side == 3, "only audited top and side requested");
            trace.append(side == 1 ? 'T' : 'S');
            int stage = side == 1 ? 1 : 2;
            invalidate(stage);
            if (failureStage == stage) {
                if (failure == 0) return null;
                if (failure == 1) throw new IOException("fixture read failure");
                uncheckedFailure();
            }
            return side == 1 ? TOP : SIDE;
        }

        private void invalidate(int stage) {
            if (invalidateStage == stage) {
                invalidateLooks.invalidate();
                live = false;
            }
        }

        private void uncheckedFailure() {
            if (failure == 2) throw new IllegalStateException("fixture callback failure");
            throw new NoClassDefFoundError("fixture linkage failure");
        }
    }

    private static void require(boolean condition, String label) {
        assertions++;
        put(label + ":" + condition);
        if (!condition) throw new AssertionError(label);
    }

    private static void put(String value) {
        digest.update(value.getBytes(StandardCharsets.UTF_8));
        digest.update((byte) '\n');
    }
}

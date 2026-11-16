package dev.diorama.gtnh.probe;

import java.lang.reflect.Field;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicReference;

import dev.diorama.bake.Looks;
import dev.diorama.bake.PreparedBlockLooks;
import dev.diorama.data.ChunkSnapshot;
import dev.diorama.gtnh.capture.ChunkCapture;
import dev.diorama.gtnh.capture.EpochFence;

/** Game-free real-core contracts, not evidence for native hooks or cross-thread publication. */
public final class EpochFenceProbe {

    private static MessageDigest hash;
    private static int assertions;
    private static int sequences;

    public static void main(String[] args) throws Exception {
        hash = MessageDigest.getInstance("SHA-256");
        lifecycle();
        resourceMutations();
        failures();
        wrongThread();
        capture();
        enumerate(new int[6], 0);
        Random random = new Random(0xC10FECE);
        for (int i = 0; i < 2000; i++) {
            int[] steps = new int[100];
            for (int j = 0; j < steps.length; j++) steps[j] = random.nextInt(9);
            oracle(steps);
        }
        StringBuilder hex = new StringBuilder();
        for (byte b : hash.digest()) {
            hex.append(Character.forDigit((b >>> 4) & 15, 16));
            hex.append(Character.forDigit(b & 15, 16));
        }
        System.out.println("FIXTURE-SHA256=" + hex);
        System.out.println(
            "PASS epoch fence; assertions=" + assertions
                + "; sequences="
                + sequences
                + "; native hooks/game/JMM UNTESTED");
    }

    private static EpochFence ready() {
        EpochFence fence = new EpochFence();
        fence.worldChanged(true);
        complete(fence);
        require(fence.session() != null, "completed resource cycle ready");
        return fence;
    }

    private static void complete(EpochFence fence) {
        fence.beginReload();
        fence.beginAtlas();
        fence.endAtlas();
        fence.endReload();
    }

    private static void lifecycle() {
        EpochFence fence = new EpochFence();
        require(fence.session() == null && !fence.current(null), "startup closed");
        fence.worldChanged(true);
        require(fence.session() == null, "world alone cannot make resources ready");
        fence.beginAtlas();
        fence.endAtlas();
        require(fence.session() == null, "initial independent stitch is not a completed manager reload");
        fence.beginReload();
        fence.endReload();
        require(fence.session() == null, "atlas from before this reload cannot satisfy its barrier");
        complete(fence);
        Object old = fence.session();
        require(old != null && old.getClass() == Object.class && fence.current(old), "opaque plain token");
        require(fence.session() == old && !fence.current(new Object()), "reuse and foreign token");
        for (int i = 0; i < 16; i++) {
            // No manager identity enters this API: a reload of the same manager still changes epoch.
            fence.beginReload();
            require(!fence.current(old) && fence.session() == null, "reload invalidates immediately");
            fence.beginReload();
            fence.beginAtlas();
            fence.beginAtlas();
            fence.endAtlas();
            require(fence.session() == null, "inner atlas end blocked");
            fence.endReload();
            require(fence.session() == null, "inner reload end blocked");
            fence.endAtlas();
            require(fence.session() == null, "outer reload outstanding");
            fence.endReload();
            Object fresh = fence.session();
            require(fresh != null && fresh != old && !fence.current(old), "outer completion fresh");
            old = fresh;
        }
        fence.worldChanged(false);
        require(fence.session() == null && !fence.current(old), "disconnect invalidates");
        fence.worldChanged(true);
        Object fresh = fence.session();
        require(fresh != null && fresh != old, "same dimension reconnect fresh");
        fence.worldChanged(true);
        require(!fence.current(fresh) && fence.session() != fresh, "repeated available notification invalidates");
        old = fence.session();
        fence.beginAtlas();
        require(!fence.current(old) && fence.session() == null, "independent atlas begin invalidates");
        fence.endAtlas();
        require(fence.session() == null, "independent stitch cannot restore manager readiness");
        complete(fence);
        require(!fence.current(old) && fence.session() != old, "atlas old token never revives");
        old = fence.session();
        fence.beginReload();
        fence.endReload();
        require(fence.session() == null && !fence.current(old), "every new reload needs its own atlas");
        complete(fence);
        fence.beginReload();
        fence.beginAtlas();
        fence.endReload();
        require(fence.session() == null, "manager return with outstanding atlas remains closed");
        fence.endAtlas();
        require(fence.session() == null, "late atlas return cannot complete an already-returned reload");
        complete(fence);
        require(fence.session() != null, "later fully observed resource cycle restores readiness");
        for (boolean freshAtlas : new boolean[] { false, true }) {
            old = fence.session();
            fence.beginReload();
            fence.beginAtlas();
            fence.endAtlas();
            // A later listener reenters reload after the outer atlas has already returned.
            fence.beginReload();
            require(fence.session() == null && !fence.current(old), "late reentrant reload invalidates earlier atlas");
            if (freshAtlas) {
                fence.beginAtlas();
                fence.endAtlas();
            }
            fence.endReload();
            require(fence.session() == null, "reentrant RETURN cannot bypass outer reload");
            fence.endReload();
            require((fence.session() != null) == freshAtlas, "late reentrant reload requires fresh atlas return");
            require(!fence.current(old), "reentrant sequence cannot revive old epoch");
            complete(fence);
        }
        oracle(new int[] { 4, 0, 2, 3, 0, 1, 1 });
        oracle(new int[] { 4, 0, 2, 3, 0, 2, 3, 1, 1 });
    }

    private static void resourceMutations() {
        EpochFence fence = ready();
        Object old = fence.session();
        fence.invalidateResources();
        require(fence.session() == null && !fence.current(old), "direct resource append revokes immediately");
        fence.worldChanged(true);
        require(fence.session() == null, "world notification cannot repair resource revocation");
        complete(fence);
        require(fence.session() != null && fence.session() != old, "complete reload recovers direct resource append");
        for (boolean afterAtlas : new boolean[] { false, true }) {
            old = fence.session();
            fence.beginReload();
            if (afterAtlas) {
                fence.beginAtlas();
                fence.endAtlas();
            }
            fence.invalidateResources();
            require(fence.session() == null && !fence.current(old), "resource append within outer reload revokes");
            if (!afterAtlas) {
                fence.beginAtlas();
                fence.endAtlas();
            }
            fence.endReload();
            require(
                (fence.session() != null) == !afterAtlas,
                "resource append requires atlas return after mutation, not an earlier completed atlas");
            require(!fence.current(old), "resource append never revives old epoch");
            complete(fence);
        }
        fence.beginAtlas();
        fence.invalidateResources();
        complete(fence);
        require(fence.session() == null, "resource revocation cannot erase outstanding atlas HEAD");
        oracle(new int[] { 4, 0, 2, 3, 1, 8, 0, 2, 3, 1 });
        oracle(new int[] { 4, 0, 8, 2, 3, 1 });
        oracle(new int[] { 4, 0, 2, 3, 8, 1 });
        for (boolean nestedReload : new boolean[] { false, true }) {
            EpochFence active = ready();
            Object token = active.session();
            active.beginReload();
            active.beginAtlas();
            if (nestedReload) {
                active.beginReload();
                active.endReload();
            } else active.invalidateResources();
            active.endAtlas();
            active.endReload();
            require(
                active.session() == null && !active.current(token),
                "mutation during in-flight atlas cannot recertify its upload");
            complete(active);
            require(active.session() == null, "in-flight atlas mutation permanently faults coordinator");
        }
        oracle(new int[] { 4, 0, 2, 8, 3, 1, 0, 2, 3, 1 });
        oracle(new int[] { 4, 0, 2, 0, 1, 3, 1, 0, 2, 3, 1 });
    }

    private static void failures() throws Exception {
        for (int missing = 0; missing < 2; missing++) {
            EpochFence fence = ready();
            Object old = fence.session();
            if (missing == 0) fence.beginReload();
            else fence.beginAtlas();
            // Simulates a throwing HEAD without RETURN, then an otherwise successful fallback.
            for (int i = 0; i < 8; i++) {
                complete(fence);
                fence.worldChanged(false);
                fence.worldChanged(true);
                require(fence.session() == null && !fence.current(old), "fallback cannot erase outstanding HEAD");
            }
        }
        for (int kind = 0; kind < 3; kind++) {
            EpochFence fence = ready();
            Object old = fence.session();
            if (kind == 0) expect(fence::endReload);
            else if (kind == 1) expect(fence::endAtlas);
            else fence.failClosed();
            complete(fence);
            fence.worldChanged(true);
            require(fence.session() == null && !fence.current(old), "permanent fault");
        }
        for (String name : new String[] { "reloadDepth", "atlasDepth" }) {
            EpochFence fence = ready();
            Object old = fence.session();
            Field depth = EpochFence.class.getDeclaredField(name);
            depth.setAccessible(true);
            depth.setInt(fence, Integer.MAX_VALUE);
            expect(name.equals("reloadDepth") ? fence::beginReload : fence::beginAtlas);
            require(depth.getInt(fence) == Integer.MAX_VALUE, "overflow never wraps");
            depth.setInt(fence, 0); // Test-only repair proves fault, not merely a huge depth, keeps it closed.
            complete(fence);
            require(fence.session() == null && !fence.current(old), "overflow fault permanent");
        }
    }

    private static void wrongThread() throws Exception {
        EpochFence fence = ready();
        Object token = fence.session();
        Runnable[] calls = { fence::beginReload, fence::endReload, fence::beginAtlas, fence::endAtlas,
            () -> fence.worldChanged(false), () -> fence.worldChanged(true), fence::failClosed,
            fence::invalidateResources, () -> fence.session(), () -> fence.current(token), () -> fence.current(null) };
        // Join only transfers probe outcomes; no claim about a worker reading the fence.
        for (Runnable call : calls) {
            AtomicReference<Throwable> result = new AtomicReference<>();
            Thread worker = new Thread(() -> {
                try {
                    call.run();
                } catch (Throwable failure) {
                    result.set(failure);
                }
            });
            worker.start();
            worker.join(5000);
            require(!worker.isAlive(), "wrong-thread call terminates");
            require(result.get() instanceof IllegalStateException, "every public operation owner-gated");
            require(fence.session() == token && fence.current(token), "wrong-thread call has zero effects");
        }
        complete(fence);
        require(fence.session() != null && !fence.current(token), "wrong-thread ends did not fault or consume depth");
    }

    private static void enumerate(int[] steps, int at) {
        if (at == steps.length) {
            oracle(steps);
            return;
        }
        for (int op = 0; op < 7; op++) {
            steps[at] = op;
            enumerate(steps, at + 1);
        }
    }

    private static void oracle(int[] steps) {
        EpochFence fence = new EpochFence();
        Model model = new Model();
        List<Object> retired = new ArrayList<>();
        Object previous = null;
        for (int op : steps) {
            boolean throwsExpected = model.apply(op);
            boolean threw = false;
            try {
                apply(fence, op);
            } catch (IllegalStateException expected) {
                threw = true;
            }
            require(threw == throwsExpected, "oracle exception parity");
            if (previous != null && op != 7) retired.add(previous);
            Object actual = fence.session();
            require((actual != null) == model.ready(), "independent stack-model readiness");
            require(!fence.current(null) && !fence.current(new Object()), "null and wrong tokens never current");
            for (Object old : retired) require(!fence.current(old), "retired token never resurrects");
            require(fence.current(actual) == (actual != null), "current session identity");
            require(fence.session() == actual, "observation reuses token");
            put(op);
            put(actual == null ? 0 : 1);
            put(threw ? 1 : 0);
            previous = actual;
        }
        sequences++;
    }

    /** Outstanding event lists deliberately differ from production's integer counters. */
    private static final class Model {

        final List<Boolean> reloads = new ArrayList<>();
        final List<Boolean> atlases = new ArrayList<>();
        boolean world;
        boolean atlasReturned;
        boolean broken;
        boolean barrierComplete;

        boolean apply(int op) {
            if (op == 0) {
                if (!atlases.isEmpty()) broken = true;
                atlasReturned = false;
                barrierComplete = false;
                reloads.add(Boolean.TRUE);
            } else if (op == 2) {
                barrierComplete = false;
                atlases.add(Boolean.TRUE);
            } else if (op == 1 || op == 3) {
                List<Boolean> stack = op == 1 ? reloads : atlases;
                if (stack.isEmpty()) {
                    broken = true;
                    return true;
                }
                stack.remove(stack.size() - 1);
                if (op == 3) atlasReturned = true;
                if (op == 1 && reloads.isEmpty() && atlases.isEmpty() && atlasReturned) barrierComplete = true;
            } else if (op == 4) world = true;
            else if (op == 5) world = false;
            else if (op == 6) broken = true;
            else if (op == 8) {
                if (!atlases.isEmpty()) broken = true;
                barrierComplete = false;
                atlasReturned = false;
            }
            // op7 is an observation, used in random sequences.
            return false;
        }

        boolean ready() {
            return world && barrierComplete && atlasReturned && reloads.isEmpty() && atlases.isEmpty() && !broken;
        }
    }

    private static void apply(EpochFence fence, int op) {
        switch (op) {
            case 0:
                fence.beginReload();
                break;
            case 1:
                fence.endReload();
                break;
            case 2:
                fence.beginAtlas();
                break;
            case 3:
                fence.endAtlas();
                break;
            case 4:
                fence.worldChanged(true);
                break;
            case 5:
                fence.worldChanged(false);
                break;
            case 6:
                fence.failClosed();
                break;
            case 8:
                fence.invalidateResources();
                break;
            default:
                break;
        }
    }

    private static void capture() {
        Scene scene = new Scene();
        ChunkCapture.Result result = ChunkCapture.capture(scene.access, scene, 0, 0);
        require(result.status() == ChunkCapture.Status.READY && result.snapshot() != null, "actual capture ready");
        require(
            result.session() == scene.epoch && scene.table.session() == scene.epoch,
            "table and result carry fence token");
        require(
            result.snapshot()
                .lookDomain() == scene.table
                && result.snapshot()
                    .sectionIds(0)[0] == 1,
            "actual prepared payload captured");
        require(scene.fence.current(result.session()), "acceptance current before lifecycle");
        scene.fence.beginReload();
        require(!scene.fence.current(result.session()), "finished result rejected after begin");
        scene.fence.beginAtlas();
        scene.fence.endAtlas();
        scene.fence.endReload();
        require(scene.fence.session() != result.session(), "finished result not revived after reload");
        ChunkCapture.Result stale = ChunkCapture.capture(scene.access, scene, 0, 0);
        require(
            stale.status() == ChunkCapture.Status.STALE_SESSION && stale.snapshot() == null,
            "old prepared table rejected by actual capture");
        require(
            result.snapshot()
                .sectionIds(0)[0] == 1,
            "old detached payload retained");
    }

    private static final class Scene implements ChunkCapture.Inputs, ChunkCapture.Chunk, ChunkCapture.Section {

        final Thread owner = Thread.currentThread();
        final EpochFence fence = ready();
        final Object epoch = fence.session();
        final PreparedBlockLooks table = new PreparedBlockLooks(epoch, 2);
        final ChunkCapture.Access access = new ChunkCapture.Access() {

            public boolean onThread() {
                return Thread.currentThread() == owner;
            }

            public boolean current() {
                return fence.session() != null;
            }

            public Object session() {
                return fence.session();
            }

            public ChunkCapture.Chunk loaded(int x, int z) {
                return Scene.this;
            }
        };

        Scene() {
            float[] face = { 0.25F, 0.5F, 1, 1 };
            require(table.cube(1, 0, face, face, 0) == 1, "fixture cube prepared");
        }

        public Object session() {
            return table.session();
        }

        public Looks looks() {
            return table;
        }

        public int airId() {
            return table.airId();
        }

        public int lookId(int raw, int meta, int x, int y, int z) {
            return table.find(raw, meta);
        }

        public ChunkSnapshot.Tints tints(int x, int z, int[] surface) {
            int[] white = new int[256];
            Arrays.fill(white, 0xFFFFFF);
            return new ChunkSnapshot.Tints(white, white, white, white);
        }

        public int fogColor(int x, int z) {
            return 0x123456;
        }

        public int chunkX() {
            return 0;
        }

        public int chunkZ() {
            return 0;
        }

        public int minSectionY() {
            return 0;
        }

        public int sectionCount() {
            return 1;
        }

        public boolean hasSkyLight() {
            return false;
        }

        public ChunkCapture.Section section(int index) {
            return this;
        }

        public int blockId(int cell) {
            return 1;
        }

        public int metadata(int cell) {
            return 0;
        }

        public int blockLight(int index, int cell) {
            return 0;
        }

        public int skyLight(int index, int cell) {
            throw new AssertionError("no sky callbacks");
        }
    }

    private static void expect(Runnable call) {
        boolean threw = false;
        try {
            call.run();
        } catch (IllegalStateException expected) {
            threw = true;
        }
        require(threw, "expected IllegalStateException");
    }

    private static void require(boolean condition, String message) {
        assertions++;
        if (!condition) throw new AssertionError(message);
    }

    private static void put(int value) {
        hash.update((byte) (value >>> 24));
        hash.update((byte) (value >>> 16));
        hash.update((byte) (value >>> 8));
        hash.update((byte) value);
    }
}

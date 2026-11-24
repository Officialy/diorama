package dev.diorama.bake;

import java.security.MessageDigest;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicReference;

/** Checks the published payload and shared geometry without a Minecraft runtime. */
public final class OverworldAppearanceProbe {

    private static final int GRASS = 90000, SNOW = 180000;
    private static final float[] CUTOUT = { .25F, .5F, 1F, .25F };
    private static final float[] OPAQUE = { 1F, .75F, .5F, 1F };
    private static int assertions;

    public static void main(String[] args) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        PreparedBlockLooks table = new PreparedBlockLooks(new Object(), 12);
        grassPlants(table, digest);
        snowLayers(table, digest);
        publicationBoundaries(table);
        StringBuilder hex = new StringBuilder();
        for (byte b : digest.digest()) {
            hex.append(Character.forDigit((b >>> 4) & 15, 16));
            hex.append(Character.forDigit(b & 15, 16));
        }
        System.out.println("FIXTURE-SHA256=" + hex);
        System.out.println(
            "PASS overworld appearance: " + assertions
                + " assertions; grass/fern PLANT tint, eight solid snow heights through shared ShapeFaces, owner/epoch/capacity; native registry/resources and game UNTESTED");
    }

    private static void grassPlants(PreparedBlockLooks table, MessageDigest digest) {
        for (int meta = 1; meta <= 2; meta++) {
            int id = table.grassPlant(GRASS, meta, CUTOUT);
            require(id == meta && table.find(GRASS, meta) == id, "grass and fern metadata have distinct prepared IDs");
            require(table.isPrepared(id) && table.kind[id] == Looks.PLANT, "no-collision plant is not AIR or SOLID");
            require(table.shape[id] == 0, "crossed plants do not publish collision/selection geometry");
            require(table.top[id] == 0 && table.side[id] == 0, "no untinted sprite contribution");
            require(
                table.topTinted[id] == 0x4080FF && table.sideTinted[id] == 0x4080FF,
                "both cross faces use the sprite");
            require(table.topTintWeight[id] == 1 && table.sideTintWeight[id] == 1, "cutout does not dilute biome tint");
            require(
                table.tint[id] == Looks.TINT_GRASS && table.constantTint[id] == -1,
                "uses biome grass, not fixed render colour");
            require(table.grassPlant(GRASS, meta, null) == id, "duplicate cannot replace a plant payload");
            put(digest, table.kind[id]);
            put(digest, table.tint[id]);
            put(digest, table.topTinted[id]);
        }
    }

    private static void snowLayers(PreparedBlockLooks table, MessageDigest digest) {
        for (int meta = 0; meta <= 7; meta++) {
            int id = table.snowLayer(SNOW, meta, OPAQUE);
            require(id == meta + 3 && table.find(SNOW, meta) == id, "each snow layer has a distinct prepared ID");
            require(
                table.isPrepared(id) && table.kind[id] == Looks.SOLID,
                "even zero-collision single-layer snow is solid");
            require(table.top[id] == 0xFFBF80 && table.side[id] == 0xFFBF80, "opaque snow sprite on both faces");
            require(
                table.tint[id] == Looks.TINT_NONE && table.topTintWeight[id] == 0 && table.sideTintWeight[id] == 0,
                "snow does not carry grass tint");
            require(table.wetShape[id] == -1 && table.emission[id] == 0, "no fluid or light inferred");
            require(table.snowLayer(SNOW, meta, null) == id, "duplicate cannot replace a snow payload");
            int shape = table.shape[id];
            put(digest, shape);
            if (meta == 7) {
                require(shape == 0, "eight layers use the full-cell sentinel, not an inset shape");
                continue;
            }
            int height = (meta + 1) * 16;
            int hi = 128 | height << 8 | 128 << 16;
            require(shape > 0, "one to seven layers have an outline, never a guessed full cube");
            ShapeFaces faces = table.faces(shape);
            require(
                Arrays.equals(faces.boxes, new int[] { 0, hi }),
                "shadow bounds use outline (not collision) height");
            require(
                Arrays.equals(faces.top, new int[] { height << 8, hi }),
                "top face is inset by the exact number of layers");
            require(Arrays.equals(faces.south, new int[] { 128 << 16, hi }), "side face ends at the same height");
            require(ShapeFaces.inset(hi, true) && !ShapeFaces.inset(hi, false), "inset top, outer south");
            put(digest, faces.top[0]);
            put(digest, faces.top[1]);
            put(digest, faces.south[0]);
            put(digest, faces.south[1]);
        }
        int other = table.snowLayer(Integer.MAX_VALUE, 0, OPAQUE);
        require(other == 11 && table.find(Integer.MAX_VALUE, 0) == other, "numeric keys preserve full raw ID");
        require(table.sameLook(other, table.find(SNOW, 0)), "equal outlines intern for appearance equivalence");
        require(!table.sameLook(other, table.find(SNOW, 1)), "changed outline is not an equivalent appearance");
    }

    private static void publicationBoundaries(PreparedBlockLooks table) throws InterruptedException {
        PreparedBlockLooks limited = new PreparedBlockLooks(new Object(), 2);
        for (int bad : new int[] { -1, 0, 3, 65535 })
            reject(() -> limited.grassPlant(GRASS, bad, CUTOUT), "invalid grass metadata remains unsupported");
        for (int bad : new int[] { -1, 8, 15, 65535 })
            reject(() -> limited.snowLayer(SNOW, bad, OPAQUE), "invalid snow metadata remains unsupported");
        reject(() -> limited.grassPlant(GRASS, 1, new float[4]), "fully transparent plant cannot become AIR");
        reject(() -> limited.snowLayer(SNOW, 0, CUTOUT), "transparent snow cannot become SOLID");
        require(
            limited.find(GRASS, 1) == -1 && limited.find(SNOW, 0) == -1 && !limited.isPrepared(1),
            "rejected appearances leave IDs pending");
        require(limited.grassPlant(GRASS, 2, CUTOUT) == 1, "invalid appearances consumed no capacity");
        require(
            limited.snowLayer(SNOW, 0, OPAQUE) == -1 && limited.shapes.length == 1,
            "capacity exhaustion cannot publish or intern a snow outline");

        int snow = table.find(SNOW, 0);
        AtomicReference<Throwable> error = new AtomicReference<>();
        Thread worker = new Thread(() -> {
            try {
                require(
                    table.isPrepared(snow) && table.faces(table.shape[snow]).top[0] == 16 << 8,
                    "worker can use an already-published snow outline");
                table.snowLayer(SNOW, 0, OPAQUE);
            } catch (Throwable failure) {
                error.set(failure);
            }
        });
        worker.start();
        worker.join();
        require(error.get() instanceof IllegalStateException, "worker cannot prepare an appearance");
        table.invalidate();
        require(table.find(SNOW, 0) == -1 && table.find(GRASS, 1) == -1, "invalidation removes both look keys");
        require(
            table.snowLayer(SNOW, 0, null) == -1 && table.grassPlant(GRASS, 1, null) == -1,
            "invalidated table cannot republish");
        require(
            table.kind[snow] == Looks.SOLID && table.faces(table.shape[snow]).top[0] == 16 << 8,
            "in-flight reader sees unchanged payload after invalidation");
    }

    private static void put(MessageDigest digest, int value) {
        for (int shift = 24; shift >= 0; shift -= 8) digest.update((byte) (value >>> shift));
    }

    private static void require(boolean ok, String description) {
        assertions++;
        if (!ok) throw new AssertionError(description);
    }

    private static void reject(Runnable action, String description) {
        try {
            action.run();
        } catch (IllegalArgumentException expected) {
            require(true, description);
            return;
        }
        throw new AssertionError(description);
    }
}

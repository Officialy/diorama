package dev.diorama.gtnh.client;

import java.io.IOException;
import java.util.Objects;

import org.jspecify.annotations.Nullable;

import dev.diorama.bake.TileImage;
import dev.diorama.bake.TileKey;
import dev.diorama.gtnh.capture.PreviewOverview;
import dev.diorama.gtnh.capture.PreviewTileStore;

/** Detached, read-only pixel arrays. An unaccepted frame never writes to disk. */
record TerrainPreviewFrame(int @Nullable [] detail, int @Nullable [] overview, @Nullable String storageFailure,
    boolean needsSave, boolean gpuApproximate) {

    TerrainPreviewFrame(int @Nullable [] detail, int @Nullable [] overview, @Nullable String storageFailure,
        boolean needsSave) {
        this(detail, overview, storageFailure, needsSave, false);
    }

    static TerrainPreviewFrame assemble(TileKey key, @Nullable TileImage image, @Nullable PreviewTileStore store,
        @Nullable TerrainPreviewFrame previous) {
        TileKey parent = key.parent();
        int @Nullable [] fresh = null;
        if (image != null) {
            int[][] mips = image.mips();
            if (!key.equals(image.key()) || mips == null || mips.length == 0)
                throw new IllegalArgumentException("Preview requires a full image for the requested tile");
            fresh = PreviewTileStore.retain(null, Objects.requireNonNull(mips[0], "preview pixels"));
        }
        int[][] children = new int[4][];
        int detailIndex = PreviewOverview.childIndex(key);
        int @Nullable [] savedDetail = null, savedOverview = null;
        String readFailure = null;
        if (store != null) {
            for (int i = 0; i < children.length; i++) {
                try {
                    children[i] = store.read(PreviewOverview.childKey(parent, i));
                } catch (IOException error) {
                    readFailure = "tile read: " + error.getClass()
                        .getSimpleName();
                }
            }
            savedDetail = children[detailIndex];
            try {
                savedOverview = store.read(parent);
            } catch (IOException error) {
                readFailure = "tile read: " + error.getClass()
                    .getSimpleName();
            }
        }
        int[] detail = PreviewTileStore.retain(savedDetail, previous == null ? null : previous.detail());
        detail = PreviewTileStore.retain(detail, fresh);
        children[detailIndex] = detail;
        int[] overview = PreviewTileStore.retain(savedOverview, previous == null ? null : previous.overview());
        overview = PreviewTileStore.retain(overview, PreviewOverview.build(parent, children));
        return new TerrainPreviewFrame(detail, overview, readFailure, store != null && fresh != null);
    }

    void save(PreviewTileStore store, TileKey key) throws IOException {
        if (!this.needsSave) return;
        // L0 is authoritative. If a later overview write fails, it can be rebuilt from L0.
        store.write(key, this.detail);
        store.write(key.parent(), this.overview);
    }
}

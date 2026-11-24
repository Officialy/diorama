package dev.diorama.gtnh.client;

import net.minecraft.world.chunk.NibbleArray;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;

import org.jspecify.annotations.Nullable;

import com.falsepattern.endlessids.config.GeneralConfig;
import com.falsepattern.endlessids.mixin.helpers.SubChunkBlockHook;

import cpw.mods.fml.common.Loader;
import dev.diorama.gtnh.capture.SectionBlocks;

/** Optional typed bridge. Only called after Loader confirms EndlessIDs is present. */
final class EndlessSectionStorage {

    private EndlessSectionStorage() {}

    static boolean enabled() {
        if (!"1.7.4".equals(
            Loader.instance()
                .getIndexedModList()
                .get("endlessids")
                .getVersion()))
            throw new UnsupportedOperationException("EndlessIDs capture adapter requires audited version 1.7.4");
        return GeneralConfig.extendBlockItem;
    }

    static void check(ExtendedBlockStorage storage, boolean extended) {
        if (GeneralConfig.extendBlockItem != extended || (storage instanceof SubChunkBlockHook) != extended)
            throw new UnsupportedOperationException("EndlessIDs configuration and applied section hooks disagree");
    }

    static SectionBlocks read(ExtendedBlockStorage storage) {
        check(storage, true);
        SubChunkBlockHook hook = (SubChunkBlockHook) storage;
        return SectionBlocks.endless(
            hook.eid$getB1(),
            bytes(hook.eid$getB2Low()),
            bytes(hook.eid$getB2High()),
            hook.eid$getB3(),
            bytes(hook.eid$getM1Low()),
            bytes(hook.eid$getM1High()),
            hook.eid$getM2());
    }

    private static byte @Nullable [] bytes(@Nullable NibbleArray array) {
        return array == null ? null : array.data;
    }

    static int blockId(ExtendedBlockStorage storage, int cell) {
        return ((SubChunkBlockHook) storage).eid$getID(cell & 15, cell >>> 8, (cell >>> 4) & 15);
    }

    static int metadata(ExtendedBlockStorage storage, int cell) {
        return ((SubChunkBlockHook) storage).eid$getMetadata(cell & 15, cell >>> 8, (cell >>> 4) & 15);
    }
}

package dev.diorama.gtnh.client;

import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;
import java.util.function.BooleanSupplier;

import net.minecraft.block.Block;
import net.minecraft.block.BlockAir;
import net.minecraft.block.BlockDirt;
import net.minecraft.block.BlockGrass;
import net.minecraft.block.BlockNewLeaf;
import net.minecraft.block.BlockOldLeaf;
import net.minecraft.block.BlockStone;
import net.minecraft.block.BlockWood;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.renderer.texture.TextureMap;
import net.minecraft.client.resources.IResource;
import net.minecraft.client.resources.IResourceManager;
import net.minecraft.init.Blocks;
import net.minecraft.util.IIcon;
import net.minecraft.util.ResourceLocation;

import org.jspecify.annotations.Nullable;

import dev.diorama.bake.PreparedBlockLooks;
import dev.diorama.gtnh.capture.GrassContext;
import dev.diorama.gtnh.capture.LegacyLeaves;
import dev.diorama.gtnh.capture.OpaquePng;

/**
 * Explicit client-thread preparation for audited vanilla cubes, leaves and grass contexts. Never
 * called by a worker or a tick hook. Unsupported renderers, contexts, animations and resources stay pending.
 * generationCurrent must cover resource reload/stitch completion, not just manager identity (the
 * vanilla resource manager is reused across reloads). It must also cover the caller's capture epoch.
 */
public final class ClientBlockAppearance {

    private final Minecraft minecraft;
    private final IResourceManager resources;
    private final TextureMap atlas;
    private final BooleanSupplier generationCurrent;
    private final PreparedBlockLooks looks;
    private boolean active = true;

    public ClientBlockAppearance(Object session, int capacity, BooleanSupplier generationCurrent) {
        this.minecraft = Minecraft.getMinecraft();
        this.requireThread();
        this.generationCurrent = Objects.requireNonNull(generationCurrent, "generationCurrent");
        this.resources = this.minecraft.getResourceManager();
        this.atlas = this.minecraft.getTextureMapBlocks();
        this.looks = new PreparedBlockLooks(session, capacity);
        if (!this.current()) throw new IllegalStateException("Appearance resource epoch is not ready");
    }

    public PreparedBlockLooks looks() {
        return this.looks;
    }

    private void requireThread() {
        if (!this.minecraft.func_152345_ab())
            throw new IllegalStateException("Appearance preparation requires the Minecraft main thread");
    }

    private boolean current() {
        this.requireThread();
        if (!this.active) return false;
        if (!this.generationCurrent.getAsBoolean() || this.minecraft.getResourceManager() != this.resources
            || this.minecraft.getTextureMapBlocks() != this.atlas) {
            this.active = false;
            this.looks.invalidate();
            return false;
        }
        return true;
    }

    /** Returns an already published ID, without preparing a missing entry. */
    public int find(int rawId, int metadata) {
        return this.find(rawId, metadata, 0);
    }

    public int find(int rawId, int metadata, int context) {
        if (!this.current()) return -1;
        if (context < 0 || context > GrassContext.SNOWY) return -1;
        if (rawId < 0 || metadata < 0 || metadata > 15) return -1;
        Block block = Block.getBlockById(rawId);
        if (block == null || Block.getIdFromBlock(block) != rawId) return -1;
        int id = block == Blocks.air && block.getClass() == BlockAir.class && context == 0 ? this.looks.airId()
            : grass(block, metadata) && context != 0 ? this.looks.find(rawId, metadata, context)
                : context == 0 && supported(block, metadata) ? this.looks.find(rawId, metadata) : -1;
        return this.current() ? id : -1;
    }

    /** Explicit preparation must precede ChunkCapture; raw IDs never imply a full-cube fallback. */
    public int prepare(int rawId, int metadata) {
        return this.prepare(rawId, metadata, 0);
    }

    public int prepare(int rawId, int metadata, int context) {
        if (!this.current()) return -1;
        if (context < 0 || context > GrassContext.SNOWY) return -1;
        if (rawId < 0 || metadata < 0 || metadata > 15) return -1;
        int existing = this.find(rawId, metadata, context);
        if (existing >= 0) return existing;
        Block block = Block.getBlockById(rawId);
        if (block == null || Block.getIdFromBlock(block) != rawId) return -1;
        if (grass(block, metadata)) return context == 0 ? -1 : this.prepareGrass(rawId, metadata, context, block);
        if (context != 0 || !supported(block, metadata)) return -1;
        if (leaf(block, metadata)) return this.prepareLeaves(rawId, metadata, block);
        float[] top = null;
        float[] side = null;
        try {
            if (block.getRenderType() != 0 || !block.isOpaqueCube()
                || !block.renderAsNormalBlock()
                || block.hasTileEntity(metadata)
                || block.getRenderColor(metadata) != 0xFFFFFF) return -1;
            top = this.average(block.getIcon(1, metadata));
            side = this.average(block.getIcon(3, metadata));
        } catch (IOException | RuntimeException unsupported) {
            top = null;
            side = null;
            // This includes close failures after pixels were read; never publish that attempt.
            // Missing/broken resource packs do not provide a ready white or missing-texture look.
        } finally {
            // Even getIcon, custom-loader probes, metadata reads and stream close can reenter mods.
            // No entry has been published yet; an epoch change invalidates all future lookups.
            this.current();
        }
        if (!this.active || top == null || side == null) return -1;
        // These exact audited classes have zero emission, no biome tint and full-cube geometry.
        return this.looks.cube(rawId, metadata, top, side, 0);
    }

    public boolean isGrass(int rawId, int metadata) {
        if (!this.current() || rawId < 0) return false;
        Block block = Block.getBlockById(rawId);
        boolean grass = block != null && Block.getIdFromBlock(block) == rawId && grass(block, metadata);
        return this.current() && grass;
    }

    private static boolean grass(Block block, int metadata) {
        return block == Blocks.grass && block.getClass() == BlockGrass.class && metadata == 0;
    }

    private int prepareGrass(int rawId, int metadata, int context, Block block) {
        float[] top = null, side = null, overlay = null;
        try {
            if (block.getRenderType() != 0 || !block.isOpaqueCube()
                || !block.renderAsNormalBlock()
                || block.hasTileEntity(metadata)
                || block.getLightValue() != 0) return -1;
            top = this.average(block.getIcon(1, metadata));
            if (!this.current()) return -1;
            side = this.average(
                context == GrassContext.SNOWY ? this.atlas.getTextureExtry("grass_side_snowed")
                    : block.getIcon(3, metadata));
            if (!this.current()) return -1;
            // Canonical map appearance includes the overlay, independent of renderer fast/fancy flags.
            if (context == GrassContext.NORMAL) overlay = this.average(BlockGrass.getIconSideOverlay(), true);
        } catch (IOException | RuntimeException unsupported) {
            top = null;
        } finally {
            this.current();
        }
        if (!this.active || top == null || side == null || (context == GrassContext.NORMAL && overlay == null))
            return -1;
        return this.looks.grass(rawId, metadata, context, top, side, overlay);
    }

    private static boolean supported(Block block, int metadata) {
        return (block == Blocks.stone && block.getClass() == BlockStone.class && metadata == 0)
            || (block == Blocks.cobblestone && block.getClass() == Block.class && metadata == 0)
            || (block == Blocks.dirt && block.getClass() == BlockDirt.class && metadata <= 1)
            || (block == Blocks.planks && block.getClass() == BlockWood.class && metadata <= 5)
            || leaf(block, metadata);
    }

    private static boolean leaf(Block block, int metadata) {
        boolean old = block == Blocks.leaves && block.getClass() == BlockOldLeaf.class;
        boolean newer = block == Blocks.leaves2 && block.getClass() == BlockNewLeaf.class;
        return (old || newer) && LegacyLeaves.sprite(newer, metadata) != null;
    }

    private int prepareLeaves(int rawId, int metadata, Block block) {
        float[] average = null;
        int fixed = -1;
        try {
            if (block.getRenderType() != 0 || !block.renderAsNormalBlock()
                || block.hasTileEntity(metadata)
                || block.getLightValue() != 0) return -1;
            boolean newer = block == Blocks.leaves2;
            String sprite = LegacyLeaves.sprite(newer, metadata);
            // Use the registered normal leaf sprite, not the graphics-dependent getIcon result.
            // This follows canonical leaf averaging without changing the game's graphics flags.
            average = this.average(this.atlas.getTextureExtry(sprite), true);
            if (LegacyLeaves.fixedTint(newer, metadata)) {
                fixed = block.getRenderColor(metadata);
                if (fixed < 0 || fixed > 0xFFFFFF) average = null;
            }
        } catch (IOException | RuntimeException unsupported) {
            average = null;
        } finally {
            this.current();
        }
        if (!this.active || average == null || average[3] <= 0) return -1;
        return this.looks.leaves(rawId, metadata, average, fixed);
    }

    private float @Nullable [] average(@Nullable IIcon icon) throws IOException {
        return this.average(icon, false);
    }

    private float @Nullable [] average(@Nullable IIcon icon, boolean cutout) throws IOException {
        if (icon == null || icon.getClass() != TextureAtlasSprite.class) return null;
        TextureAtlasSprite sprite = (TextureAtlasSprite) icon;
        String name = sprite.getIconName();
        if (name == null || name.equals("missingno")
            || this.atlas.getAtlasSprite(name) != sprite
            || this.atlas.getTextureExtry(name) != sprite
            || sprite.hasAnimationMetadata()) return null;
        ResourceLocation logical = new ResourceLocation(name);
        if (sprite.hasCustomLoader(this.resources, logical)) return null;
        ResourceLocation location = new ResourceLocation(
            logical.getResourceDomain(),
            "textures/blocks/" + logical.getResourcePath() + ".png");
        IResource resource = this.resources.getResource(location);
        try (InputStream stream = resource.getInputStream()) {
            if (resource.getMetadata("animation") != null) return null;
            return cutout ? OpaquePng.averageCutout(stream, sprite.getIconWidth(), sprite.getIconHeight())
                : OpaquePng.average(stream, sprite.getIconWidth(), sprite.getIconHeight());
        }
    }
}

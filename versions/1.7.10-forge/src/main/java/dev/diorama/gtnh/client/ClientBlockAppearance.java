package dev.diorama.gtnh.client;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BooleanSupplier;

import net.minecraft.block.Block;
import net.minecraft.block.BlockAir;
import net.minecraft.block.BlockDirt;
import net.minecraft.block.BlockDynamicLiquid;
import net.minecraft.block.BlockGrass;
import net.minecraft.block.BlockNewLeaf;
import net.minecraft.block.BlockOldLeaf;
import net.minecraft.block.BlockSlab;
import net.minecraft.block.BlockSnow;
import net.minecraft.block.BlockStairs;
import net.minecraft.block.BlockStaticLiquid;
import net.minecraft.block.BlockTallGrass;
import net.minecraft.block.material.Material;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.renderer.texture.TextureMap;
import net.minecraft.client.resources.IResource;
import net.minecraft.client.resources.IResourceManager;
import net.minecraft.client.resources.data.AnimationMetadataSection;
import net.minecraft.client.resources.data.IMetadataSection;
import net.minecraft.init.Blocks;
import net.minecraft.util.IIcon;
import net.minecraft.util.ResourceLocation;
import net.minecraft.world.IBlockAccess;

import org.jspecify.annotations.Nullable;

import dev.diorama.bake.Looks;
import dev.diorama.bake.PreparedBlockLooks;
import dev.diorama.bake.SpriteAverage;
import dev.diorama.gtnh.capture.GrassContext;
import dev.diorama.gtnh.capture.LegacyGeometryMath;
import dev.diorama.gtnh.capture.LegacyLeaves;
import dev.diorama.gtnh.capture.OpaquePng;
import dev.diorama.gtnh.capture.StaticCubeAppearance;
import dev.diorama.gtnh.capture.WaterPng;

/**
 * Explicit client-thread preparation for static opaque cubes and audited vanilla leaves, grass, water,
 * grass plants and snow layers.
 * Never called by a worker or a tick hook. Animations stay pending except the water-only first-frame
 * path; unsupported renderers, contexts, custom loaders and resources stay pending.
 * generationCurrent must cover resource reload/stitch completion, not just manager identity (the
 * vanilla resource manager is reused across reloads). It must also cover the caller's capture epoch.
 */
public final class ClientBlockAppearance {

    private final Minecraft minecraft;
    private final IResourceManager resources;
    private final TextureMap atlas;
    private final BooleanSupplier generationCurrent;
    private final PreparedBlockLooks looks;
    private final Map<IIcon, float[]> fallbackSprites = new IdentityHashMap<>();
    private final Map<Integer, List<TextureAtlasSprite>> atlasBins = new HashMap<>();
    private boolean atlasIndexed;
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
            LegacyGeometryCapture.clear();
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
        if (rawId < 0 || metadata < 0 || metadata > 65535) return -1;
        Block block = Block.getBlockById(rawId);
        if (block == null || Block.getIdFromBlock(block) != rawId) return -1;
        int geometry = context == 0 ? this.looks.findObserved(rawId, metadata) : -1;
        if (geometry >= 0) return geometry;
        int id = block == Blocks.air && block.getClass() == BlockAir.class && context == 0 ? this.looks.airId()
            : grass(block, metadata) && context != 0 ? this.looks.find(rawId, metadata, context)
                : context == 0 ? this.looks.find(rawId, metadata) : -1;
        return this.current() ? id : -1;
    }

    /** Explicit preparation must precede ChunkCapture; raw IDs never imply a full-cube fallback. */
    public int prepare(int rawId, int metadata) {
        return this.prepare(rawId, metadata, 0);
    }

    public int prepare(int rawId, int metadata, int context) {
        if (!this.current()) return -1;
        if (context < 0 || context > GrassContext.SNOWY) return -1;
        if (rawId < 0 || metadata < 0 || metadata > 65535) return -1;
        if (context == 0 && this.looks.findObserved(rawId, metadata) < 0) {
            float[] observed = LegacyGeometryCapture.sample(rawId, metadata);
            if (observed != null) {
                Block candidate = Block.getBlockById(rawId);
                if (candidate != null && Block.getIdFromBlock(candidate) == rawId && !grass(candidate, metadata)) {
                    int captured = this.prepareApproximate(rawId, metadata, candidate, observed);
                    if (captured >= 0) return captured;
                }
            }
        }
        int existing = this.find(rawId, metadata, context);
        if (existing >= 0) return existing;
        Block block = Block.getBlockById(rawId);
        if (block == null || Block.getIdFromBlock(block) != rawId) return -1;
        if (grass(block, metadata)) return context == 0 ? -1 : this.prepareGrass(rawId, metadata, context, block);
        if (context != 0) return -1;
        int audited = -1;
        if (leaf(block, metadata)) audited = this.prepareLeaves(rawId, metadata, block);
        else if (water(block, metadata)) audited = this.prepareWater(rawId, metadata, block);
        else if (block == Blocks.tallgrass && block.getClass() == BlockTallGrass.class
            && (metadata == 1 || metadata == 2)) audited = this.prepareOverworld(rawId, metadata, block, false);
        else if (block == Blocks.snow_layer && block.getClass() == BlockSnow.class && metadata <= 7)
            audited = this.prepareOverworld(rawId, metadata, block, true);
        if (audited >= 0) return audited;
        int strict = StaticCubeAppearance.prepare(this.looks, rawId, metadata, new StaticCubeAppearance.Access() {

            @Override
            public boolean eligible() {
                return staticCube(block, metadata);
            }

            @Override
            public float[] average(int side) throws IOException {
                return ClientBlockAppearance.this.average(block.getIcon(side, metadata));
            }
        }, this::current);
        return strict >= 0 ? strict : this.prepareApproximate(rawId, metadata, block);
    }

    /** State-only fallback: never invokes a world renderer or reads neighbouring chunks. */
    private int prepareApproximate(int rawId, int metadata, Block block) {
        return this.prepareApproximate(rawId, metadata, block, null);
    }

    private int prepareApproximate(int rawId, int metadata, Block block, float @Nullable [] observed) {
        if (!this.current() || block.getMaterial() == Material.air) return -1;
        try {
            int renderType = block.getRenderType();
            Material material = block.getMaterial();
            // Tile-only solid blocks may still have a representative map/inventory colour.
            if (renderType < 0 && (!block.hasTileEntity(metadata) || !material.isSolid())) return -1;
            byte kind = material == Material.water ? Looks.WATER
                : material == Material.lava ? Looks.LAVA
                    : (material == Material.glass || material == Material.ice) && !block.isOpaqueCube() ? Looks.GLASS
                        : renderType == 1 || renderType == 6 || renderType == 19 || renderType == 20 || renderType == 37
                            ? Looks.PLANT
                            : Looks.SOLID;
            float[] top = this.fallbackFace(block, metadata, 1);
            float[] side = this.fallbackFace(block, metadata, 3);
            if (top == null) top = side;
            if (side == null) side = top;
            if (top == null) {
                int color = block.getMapColor(metadata).colorValue;
                top = rgb(color);
                side = top;
            } else {
                int tint = block.getRenderColor(metadata);
                top = multiply(top, tint);
                side = multiply(side, tint);
            }
            if (observed != null) {
                float[] colours = this.observedColours(observed);
                if (colours[3] > 0) top = new float[] { colours[0], colours[1], colours[2], 1 };
                if (colours[7] > 0) side = new float[] { colours[4], colours[5], colours[6], 1 };
            }
            boolean shaped = kind == Looks.SOLID || kind == Looks.GLASS;
            if (observed != null && !shaped) return -1;
            float[] boxes = shaped
                ? observed != null ? LegacyGeometryMath.bounds(observed) : legacyBoxes(block, metadata)
                : null;
            if (observed != null && boxes == null) return -1;
            int light = Math.max(0, Math.min(15, block.getLightValue()));
            return this.current()
                ? this.looks.approximate(rawId, metadata, observed == null ? 0 : 3, top, side, light, kind, boxes)
                : -1;
        } catch (RuntimeException | LinkageError unsupported) {
            return -1;
        }
    }

    /** Build a bounded UV lookup once per resource epoch, without reading GPU texture state. */
    private void indexAtlas() {
        if (this.atlasIndexed) return;
        this.atlasIndexed = true;
        try {
            Field field;
            try {
                field = TextureMap.class.getDeclaredField("mapUploadedSprites");
            } catch (NoSuchFieldException mapped) {
                field = TextureMap.class.getDeclaredField("field_94252_e");
            }
            field.setAccessible(true);
            if (!(field.get(this.atlas) instanceof Map<?, ?>uploaded) || uploaded.size() > 65536) return;
            int count = 0;
            for (Object value : uploaded.values()) {
                if (!(value instanceof TextureAtlasSprite sprite) || "missingno".equals(sprite.getIconName())) continue;
                int x0 = bin(sprite.getMinU()), x1 = bin(sprite.getMaxU());
                int y0 = bin(sprite.getMinV()), y1 = bin(sprite.getMaxV());
                if (x0 > x1 || y0 > y1) continue;
                for (int y = y0; y <= y1; y++) {
                    for (int x = x0; x <= x1; x++) {
                        if (++count > 262144) {
                            this.atlasBins.clear();
                            return;
                        }
                        this.atlasBins.computeIfAbsent(y * 32 + x, ignored -> new ArrayList<>())
                            .add(sprite);
                    }
                }
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError unsupported) {
            this.atlasBins.clear();
        }
    }

    private static int bin(float coordinate) {
        return Math.max(0, Math.min(31, (int) (coordinate * 32)));
    }

    /** Resolve a quad to one stitched sprite, rejecting ambiguous/multi-sprite UVs. */
    private @Nullable TextureAtlasSprite sprite(float[] quad) {
        this.indexAtlas();
        List<TextureAtlasSprite> candidates = this.atlasBins
            .get(bin((quad[1] + quad[3]) * 0.5F) * 32 + bin((quad[0] + quad[2]) * 0.5F));
        if (candidates == null) return null;
        TextureAtlasSprite match = null;
        for (TextureAtlasSprite sprite : candidates) {
            if (quad[0] + 0.000001F < sprite.getMinU() || quad[1] + 0.000001F < sprite.getMinV()
                || quad[2] - 0.000001F > sprite.getMaxU()
                || quad[3] - 0.000001F > sprite.getMaxV()) continue;
            if (match != null && match != sprite) return null;
            match = sprite;
        }
        return match;
    }

    /** Texture colour only: the shared baker adds its own lighting; do not double-shade. */
    private float[] observedColours(float[] sample) {
        float[] result = new float[8];
        for (int i = 0; i < sample.length; i += 20) {
            float[] quad = LegacyGeometryMath.quad(sample, i);
            TextureAtlasSprite sprite = this.sprite(quad);
            if (sprite == null) continue;
            float[] colour = this.sampleSprite(sprite, quad);
            if (colour == null || colour[3] <= 0) continue;
            for (int face = 0; face < 2; face++) {
                int base = face * 4;
                float weight = quad[4 + face] * colour[3];
                for (int channel = 0; channel < 3; channel++) result[base + channel] += colour[channel] * weight;
                result[base + 3] += weight;
            }
        }
        for (int face = 0; face < 2; face++) {
            int base = face * 4;
            if (result[base + 3] > 0) {
                for (int channel = 0; channel < 3; channel++) result[base + channel] /= result[base + 3];
            }
        }
        return result;
    }

    /** Sample a bounded 8x8 grid within the observed UV rectangle, with a full-sprite fallback. */
    private float @Nullable [] sampleSprite(TextureAtlasSprite sprite, float[] quad) {
        try {
            int width = sprite.getIconWidth(), height = sprite.getIconHeight();
            if (width > 0 && height > 0 && (long) width * height <= 1048576L && sprite.getFrameCount() > 0) {
                int[][] frame = sprite.getFrameTextureData(0);
                float du = sprite.getMaxU() - sprite.getMinU(), dv = sprite.getMaxV() - sprite.getMinV();
                if (du > 0 && dv > 0
                    && frame != null
                    && frame.length > 0
                    && frame[0] != null
                    && frame[0].length == width * height) {
                    SpriteAverage average = new SpriteAverage();
                    for (int y = 0; y < 8; y++) {
                        for (int x = 0; x < 8; x++) {
                            float u = quad[0] + (quad[2] - quad[0]) * (x + 0.5F) / 8;
                            float v = quad[1] + (quad[3] - quad[1]) * (y + 0.5F) / 8;
                            int px = Math.max(0, Math.min(width - 1, (int) ((u - sprite.getMinU()) / du * width)));
                            int py = Math.max(0, Math.min(height - 1, (int) ((v - sprite.getMinV()) / dv * height)));
                            average.add(frame[0][py * width + px]);
                        }
                    }
                    return average.finish(64);
                }
            }
            return this.fallbackSprite(sprite);
        } catch (RuntimeException | LinkageError unsupported) {
            return null;
        }
    }

    private static float[] rgb(int color) {
        return new float[] { ((color >> 16) & 255) / 255F, ((color >> 8) & 255) / 255F, (color & 255) / 255F, 1 };
    }

    private static float[] multiply(float[] face, int tint) {
        float[] color = rgb(tint);
        return new float[] { face[0] * color[0], face[1] * color[1], face[2] * color[2], face[3] };
    }

    private float @Nullable [] fallbackFace(Block block, int metadata, int side) {
        try {
            return this.fallbackSprite(block.getIcon(side, metadata));
        } catch (RuntimeException | LinkageError unsupported) {
            return null;
        }
    }

    private float @Nullable [] fallbackSprite(@Nullable IIcon icon) {
        if (icon == null) return null;
        float[] cached = this.fallbackSprites.get(icon);
        if (cached != null) return cached[3] > 0 ? cached : null;
        float[] result = null;
        try {
            if (icon instanceof TextureAtlasSprite sprite && !"missingno".equals(sprite.getIconName())) {
                int width = sprite.getIconWidth(), height = sprite.getIconHeight();
                if (width > 0 && height > 0 && (long) width * height <= 1048576L && sprite.getFrameCount() > 0) {
                    int[][] frame = sprite.getFrameTextureData(0);
                    if (frame != null && frame.length > 0 && frame[0] != null && frame[0].length == width * height) {
                        SpriteAverage average = new SpriteAverage();
                        for (int pixel : frame[0]) average.add(pixel);
                        result = average.finish(frame[0].length);
                    }
                }
                // Static sprites can discard CPU frame data after stitching. Use the ordinary
                // source resource only when it really represents this registered sprite.
                if ((result == null || result[3] <= 0) && this.atlas.getAtlasSprite(sprite.getIconName()) == sprite) {
                    ResourceLocation logical = new ResourceLocation(sprite.getIconName());
                    if (!sprite.hasCustomLoader(this.resources, logical)) {
                        ResourceLocation location = new ResourceLocation(
                            logical.getResourceDomain(),
                            "textures/blocks/" + logical.getResourcePath() + ".png");
                        IResource resource = this.resources.getResource(location);
                        try (InputStream stream = resource.getInputStream()) {
                            result = WaterPng.averageSpriteFrame(
                                stream,
                                width,
                                height,
                                resource.getMetadata("animation") != null || sprite.hasAnimationMetadata());
                        }
                    }
                }
            }
            if (result == null || result[3] <= 0) result = this.average(icon, true);
        } catch (IOException | RuntimeException | LinkageError unsupported) {
            // Unsupported resource paths remain eligible for a map-colour fallback.
        }
        if (!this.current()) return null;
        if (this.fallbackSprites.size() < 8192) this.fallbackSprites.put(icon, result == null ? new float[4] : result);
        return result != null && result[3] > 0 ? result : null;
    }

    /** Metadata-only straight stair/slab outlines. Connected corners need a contextual capture. */
    private static float @Nullable [] legacyBoxes(Block block, int metadata) {
        if (block instanceof BlockSlab && !block.isOpaqueCube()) {
            float low = (metadata & 8) == 0 ? 0 : 0.5F;
            return new float[] { 0, low, 0, 1, low + 0.5F, 1 };
        }
        if (block instanceof BlockStairs) {
            float low = (metadata & 4) == 0 ? 0 : 0.5F;
            float step = 0.5F - low;
            float[] boxes = { 0, low, 0, 1, low + 0.5F, 1, 0, step, 0, 1, step + 0.5F, 1 };
            switch (metadata & 3) {
                case 0 -> boxes[6] = 0.5F;
                case 1 -> boxes[9] = 0.5F;
                case 2 -> boxes[8] = 0.5F;
                case 3 -> boxes[11] = 0.5F;
                default -> throw new IllegalStateException("Invalid stair direction");
            }
            return boxes;
        }
        if (block == Blocks.carpet) return new float[] { 0, 0, 0, 1, 0.0625F, 1 };
        if (block == Blocks.snow_layer && metadata <= 7) return new float[] { 0, 0, 0, 1, (metadata + 1) / 8F, 1 };
        if (block == Blocks.stone_pressure_plate || block == Blocks.wooden_pressure_plate
            || block == Blocks.light_weighted_pressure_plate
            || block == Blocks.heavy_weighted_pressure_plate)
            return new float[] { 0.0625F, 0, 0.0625F, 0.9375F, 0.0625F, 0.9375F };
        return LegacyGeometryMath.basicBoxes(block.getRenderType(), metadata);
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

    /** A conservative generic path, not a per-mod colour table or a full renderer emulator. */
    private static boolean staticCube(Block block, int metadata) {
        Class<?> type = block.getClass();
        Class<?>[] position = { IBlockAccess.class, int.class, int.class, int.class };
        // Default world methods are essential: inventory colour/icon/light alone prove nothing
        // about a mod's biome, neighbour or tile-entity dependent rendering.
        if (!StaticCubeAppearance
            .inherits(type, Block.class, new String[] { "colorMultiplier", "func_149720_d" }, position)
            || !StaticCubeAppearance.inherits(type, Block.class, new String[] { "getLightValue" }, position)
            || !StaticCubeAppearance
                .inherits(type, Block.class, new String[] { "setBlockBoundsBasedOnState", "func_149719_a" }, position)
            // Exact vanilla dirt overrides world icons only for podzol (meta 2), not meta 0/1.
            || (!(block == Blocks.dirt && type == BlockDirt.class && metadata >= 0 && metadata <= 1)
                && !StaticCubeAppearance.inherits(
                    type,
                    Block.class,
                    new String[] { "getIcon", "func_149673_e" },
                    IBlockAccess.class,
                    int.class,
                    int.class,
                    int.class,
                    int.class))
            || !StaticCubeAppearance.inherits(type, Block.class, new String[] { "canRenderInPass" }, int.class))
            return false;
        return block.getRenderType() == 0 && block.isOpaqueCube()
            && block.renderAsNormalBlock()
            && !block.hasTileEntity(metadata)
            && block.getRenderBlockPass() == 0
            && block.getRenderColor(metadata) == 0xFFFFFF
            && block.getLightValue() == 0
            && StaticCubeAppearance.unitBounds(
                block.getBlockBoundsMinX(),
                block.getBlockBoundsMinY(),
                block.getBlockBoundsMinZ(),
                block.getBlockBoundsMaxX(),
                block.getBlockBoundsMaxY(),
                block.getBlockBoundsMaxZ());
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

    private static boolean water(Block block, int metadata) {
        return metadata >= 0 && metadata <= 15
            && ((block == Blocks.water && block.getClass() == BlockStaticLiquid.class)
                || (block == Blocks.flowing_water && block.getClass() == BlockDynamicLiquid.class));
    }

    private int prepareWater(int rawId, int metadata, Block block) {
        float[] average = null;
        try {
            if (block.getMaterial() != Material.water || block.getRenderType() != 4
                || block.isOpaqueCube()
                || block.renderAsNormalBlock()
                || block.hasTileEntity(metadata)
                || block.getLightValue() != 0) return -1;
            // Canonical BlockLooks uses the still texture for every pure-water cell, including flow.
            // Legacy sloped faces/flow UVs are not the shared map tracer's liquid-cell semantics.
            average = this.averageWater(block.getIcon(1, metadata));
        } catch (IOException | RuntimeException unsupported) {
            average = null;
        } finally {
            // Includes close failures and callbacks while probing animation/custom-loader metadata.
            this.current();
        }
        if (!this.active || average == null || average[3] <= 0) return -1;
        return this.looks.water(rawId, metadata, average);
    }

    private int prepareOverworld(int rawId, int metadata, Block block, boolean snow) {
        float[] average = null;
        try {
            if (block.getRenderType() != (snow ? 0 : 1) || block.isOpaqueCube()
                || block.renderAsNormalBlock()
                || block.hasTileEntity(metadata)
                || block.getRenderBlockPass() != 0
                || block.getLightValue() != 0) return -1;
            // These exact vanilla states use the same sprite for all visible faces (snow) or
            // for both unculled cross planes (grass/fern). Unsupported sprites remain pending.
            average = this.average(block.getIcon(1, metadata), !snow);
        } catch (IOException | RuntimeException unsupported) {
            average = null;
        } finally {
            this.current();
        }
        if (!this.active || average == null || average[3] <= 0) return -1;
        return snow ? this.looks.snowLayer(rawId, metadata, average) : this.looks.grassPlant(rawId, metadata, average);
    }

    private float @Nullable [] averageWater(@Nullable IIcon icon) throws IOException {
        if (icon == null || icon.getClass() != TextureAtlasSprite.class) return null;
        TextureAtlasSprite sprite = (TextureAtlasSprite) icon;
        String name = sprite.getIconName();
        if (name == null || this.atlas.getAtlasSprite(name) != sprite || this.atlas.getTextureExtry(name) != sprite)
            return null;
        ResourceLocation logical = new ResourceLocation(name);
        // This is the only animation exception, restricted to the audited vanilla still-water sprite.
        if (!logical.getResourceDomain()
            .equals("minecraft")
            || !logical.getResourcePath()
                .equals("water_still")
            || sprite.hasCustomLoader(this.resources, logical)
            || !this.current()) return null;
        IResource resource = this.resources
            .getResource(new ResourceLocation("minecraft", "textures/blocks/water_still.png"));
        try (InputStream stream = resource.getInputStream()) {
            IMetadataSection animation = resource.getMetadata("animation");
            if ((animation != null && animation.getClass() != AnimationMetadataSection.class)
                || sprite.hasAnimationMetadata() != (animation != null)
                || !this.current()) return null;
            return WaterPng.averageFirstFrame(stream, sprite.getIconWidth(), sprite.getIconHeight(), animation != null);
        }
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

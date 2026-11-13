package dev.diorama.gtnh.loading;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.gtnewhorizon.gtnhmixins.IEarlyMixinLoader;

import cpw.mods.fml.relauncher.IFMLLoadingPlugin;

/** No client class references: the config contains client-only lifecycle hooks. */
@IFMLLoadingPlugin.MCVersion("1.7.10")
public final class DioramaEarlyMixins implements IFMLLoadingPlugin, IEarlyMixinLoader {

    @Override
    public String getMixinConfig() {
        return "mixins.diorama.json";
    }

    @Override
    public List<String> getMixins(Set<String> loadedCoreMods) {
        return Collections.emptyList();
    }

    @Override
    public String[] getASMTransformerClass() {
        return new String[0];
    }

    @Override
    public String getModContainerClass() {
        return null;
    }

    @Override
    public String getSetupClass() {
        return null;
    }

    @Override
    public void injectData(Map<String, Object> data) {}

    @Override
    public String getAccessTransformerClass() {
        return null;
    }
}

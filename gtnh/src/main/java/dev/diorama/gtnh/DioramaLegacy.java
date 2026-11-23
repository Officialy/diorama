package dev.diorama.gtnh;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.SidedProxy;
import cpw.mods.fml.common.event.FMLInitializationEvent;

/** Forge entry point; terrain diagnostics are client-only. */
@Mod(
    modid = "diorama",
    name = "Diorama",
    version = "0.8.1-gtnh.19-dev",
    acceptableRemoteVersions = "*",
    dependencies = "required-after:gtnhlib@[0.11.48,);required-after:unimixins@[0.3.1,)")
public final class DioramaLegacy {

    @SidedProxy(clientSide = "dev.diorama.gtnh.client.ClientProxy", serverSide = "dev.diorama.gtnh.CommonProxy")
    public static CommonProxy proxy;

    private static final Logger LOG = LogManager.getLogger("Diorama");

    @Mod.EventHandler
    public void init(FMLInitializationEvent event) {
        proxy.init();
        LOG.info(
            "Diorama legacy diagnostic build loaded; client commands /dioramaprobe and /dioramaterrain available.");
    }
}

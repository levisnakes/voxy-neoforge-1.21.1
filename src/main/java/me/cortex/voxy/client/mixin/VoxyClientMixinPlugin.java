package me.cortex.voxy.client.mixin;

import net.neoforged.fml.loading.LoadingModList;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

/**
 * Gates optional-mod mixins so they only apply when the target mod is installed.
 * Currently used for the Iris shader integration (me.cortex.voxy.client.mixin.iris.*).
 */
public class VoxyClientMixinPlugin implements IMixinConfigPlugin {
    private static final String IRIS_MIXIN_PACKAGE = "me.cortex.voxy.client.mixin.iris.";

    private static boolean isModLoading(String modId) {
        try {
            var modList = LoadingModList.get();
            return modList != null && modList.getModFileById(modId) != null;
        } catch (Throwable t) {
            return false;
        }
    }

    private static final boolean IRIS_PRESENT = isModLoading("iris");

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        if (mixinClassName.startsWith(IRIS_MIXIN_PACKAGE)) {
            return IRIS_PRESENT;
        }
        return true;
    }

    @Override
    public void onLoad(String mixinPackage) {
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
    }

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }
}

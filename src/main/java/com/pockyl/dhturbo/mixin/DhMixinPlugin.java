package com.pockyl.dhturbo.mixin;

import net.minecraftforge.fml.loading.FMLEnvironment;
import net.minecraftforge.fml.loading.LoadingModList;
import net.minecraftforge.fml.loading.moddiscovery.ModFileInfo;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;
import org.spongepowered.asm.service.MixinService;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Applies the Distant Horizons mixins only when Distant Horizons is installed (it is optional for the game test
 * server) and only when DH still has what they hook into.
 * <p>
 * The mixins target DH internals, which change between DH versions. Injectors are optional ({@code defaultRequire 0}),
 * but a missing shadowed field or method would crash the game when the class loads. So before a mixin is applied, the
 * target's bytecode is checked for every member the mixin uses (and, for the plant colour hooks, the number of call
 * sites their ordinals count on); a mixin whose requirements are not met is skipped with a warning naming the feature
 * that is off. Mixins that work together are skipped together.
 */
public final class DhMixinPlugin implements IMixinConfigPlugin {
    private static final Logger LOGGER = LoggerFactory.getLogger("dhturbo");
    private static final boolean DISTANT_HORIZONS = LoadingModList.get().getModFileById("distanthorizons") != null;

    /** Something a mixin needs in its target class. */
    private record Requirement(String member, String callee, int calls) {
        static Requirement member(String name) {
            return new Requirement(name, null, 0);
        }

        /** {@code calls} invocations of {@code callee} inside method {@code member}. */
        static Requirement calls(String method, String callee, int calls) {
            return new Requirement(method, callee, calls);
        }
    }

    private record Hook(String target, String feature, List<Requirement> requirements, List<String> needs) {
    }

    private static final Map<String, Hook> HOOKS = Map.of(
            "AbstractLodRequestStateMixin", new Hook("com.seibel.distanthorizons.core.generation.queues.AbstractLodRequestState",
                    "client-side generation in multiplayer",
                    List.of(Requirement.member("<init>"), Requirement.member("dhLevel"), Requirement.member("retrievalQueue")),
                    List.of("DhClientLevelMixin")),
            "DhClientLevelMixin", new Hook("com.seibel.distanthorizons.core.level.DhClientLevel",
                    "client-side generation in multiplayer",
                    List.of(Requirement.member("shouldDoWorldGen"), Requirement.member("networkState"),
                            Requirement.member("levelWrapper"), Requirement.member("clientside")),
                    List.of("AbstractLodRequestStateMixin")),
            "FullDataSourceV2Mixin", new Hook("com.seibel.distanthorizons.core.dataObjects.fullData.sources.FullDataSourceV2",
                    "keeping coarse terrain while finer tiles generate",
                    List.of(Requirement.member("updateFromOneBelowDetailLevel"), Requirement.member("getPos"),
                            Requirement.member("dataPoints"), Requirement.member("columnGenerationSteps"),
                            Requirement.member("columnWorldCompressionMode")),
                    List.of()),
            "FullDataToRenderDataTransformerMixin", new Hook("com.seibel.distanthorizons.core.dataObjects.transformers.FullDataToRenderDataTransformer",
                    "soft plant colours",
                    List.of(Requirement.calls("setRenderColumnView", "getBlockColor", 4),
                            Requirement.calls("setRenderColumnView", "setAlpha", 2),
                            Requirement.calls("setRenderColumnView", "getMaterialId", 2)),
                    List.of()),
            "LodQuadTreeMixin", new Hook("com.seibel.distanthorizons.core.render.QuadTree.LodQuadTree",
                    "no vanishing terrain while LODs change detail",
                    List.of(Requirement.member("onDesiredDetailLevel"), Requirement.member("tickNodeHolder")),
                    List.of("LodRenderSectionMixin", "QuadTreeTickNodeHolderMixin")),
            "LodRenderSectionMixin", new Hook("com.seibel.distanthorizons.core.render.QuadTree.LodRenderSection",
                    "no vanishing terrain while LODs change detail",
                    List.of(Requirement.member("getAndBuildRenderData")),
                    List.of("LodQuadTreeMixin")),
            "QuadTreeTickNodeHolderMixin", new Hook("com.seibel.distanthorizons.core.render.QuadTree.QuadTreeTickNodeHolder",
                    "no vanishing terrain while LODs change detail",
                    List.of(Requirement.member("getWorldGenNodesNearToFar"), Requirement.member("clear")),
                    List.of("LodQuadTreeMixin"))
    );

    /** Per mixin: missing requirements, empty when it can be applied. */
    private static final Map<String, List<String>> MISSING = new ConcurrentHashMap<>();

    @Override
    public void onLoad(String mixinPackage) {
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        if (targetClassName.startsWith("net.minecraft.")) {
            // Hooks into vanilla (structures on the virtual level) do not depend on Distant Horizons.
            return true;
        }
        if (!DISTANT_HORIZONS) {
            return false;
        }
        String mixin = mixinClassName.substring(mixinClassName.lastIndexOf('.') + 1);
        Hook hook = HOOKS.get(mixin);
        if (hook == null) {
            return true;
        }
        List<String> missing = new ArrayList<>(missing(mixin));
        for (String partner : hook.needs) {
            if (!missing(partner).isEmpty()) {
                missing.add("its partner " + partner);
            }
        }
        if (missing.isEmpty()) {
            if (!FMLEnvironment.production) {
                LOGGER.info("DH Turbo hook {} matches Distant Horizons {}", mixin, dhVersion());
            }
            return true;
        }
        LOGGER.warn("Distant Horizons {} is not what DH Turbo was made for: {} is off ({} skipped, missing {})",
                dhVersion(), hook.feature, mixin, String.join(", ", missing));
        return false;
    }

    private static List<String> missing(String mixin) {
        return MISSING.computeIfAbsent(mixin, key -> {
            Hook hook = HOOKS.get(key);
            if (hook == null) {
                return List.of();
            }
            ClassNode node;
            try {
                node = MixinService.getService().getBytecodeProvider().getClassNode(hook.target);
            } catch (Exception e) {
                return List.of("class " + hook.target);
            }
            List<String> result = new ArrayList<>();
            for (Requirement requirement : hook.requirements) {
                if (requirement.callee == null) {
                    boolean found = node.methods.stream().anyMatch(method -> method.name.equals(requirement.member))
                            || node.fields.stream().anyMatch(field -> field.name.equals(requirement.member));
                    if (!found) {
                        result.add(requirement.member);
                    }
                } else {
                    int calls = node.methods.stream().filter(method -> method.name.equals(requirement.member))
                            .mapToInt(method -> countCalls(method, requirement.callee)).sum();
                    if (calls != requirement.calls) {
                        result.add(requirement.calls + " calls of " + requirement.callee + " in " + requirement.member
                                + " (found " + calls + ")");
                    }
                }
            }
            return result;
        });
    }

    private static int countCalls(MethodNode method, String callee) {
        int calls = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call && call.name.equals(callee)) {
                calls++;
            }
        }
        return calls;
    }

    private static String dhVersion() {
        ModFileInfo file = LoadingModList.get().getModFileById("distanthorizons");
        return file == null || file.getMods().isEmpty() ? "?" : file.getMods().get(0).getVersion().toString();
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

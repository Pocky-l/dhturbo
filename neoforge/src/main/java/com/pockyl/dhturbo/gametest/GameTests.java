package com.pockyl.dhturbo.gametest;

import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.FunctionGameTestInstance;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestData;
import net.minecraft.gametest.framework.TestEnvironmentDefinition;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.block.Rotation;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHooks;
import net.neoforged.neoforge.registries.DeferredRegister;

import com.pockyl.dhturbo.DhTurbo;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/** Registers the {@link GameTest} methods of {@link ModGameTests} with the registry based game test framework. */
public final class GameTests {
    private static final DeferredRegister<Consumer<GameTestHelper>> FUNCTIONS =
            DeferredRegister.create(Registries.TEST_FUNCTION, DhTurbo.MOD_ID);

    private record Test(String name, Method method, GameTest settings) {
    }

    private GameTests() {
    }

    /** Does nothing unless the game test framework is on (the game test server and dev runs that enable it). */
    public static void register(IEventBus modBus) {
        if (!GameTestHooks.isGametestEnabled()) {
            return;
        }
        List<Test> tests = collect();
        for (Test test : tests) {
            FUNCTIONS.register(test.name(), () -> helper -> run(test.method(), helper));
        }
        FUNCTIONS.register(modBus);
        modBus.addListener((RegisterGameTestsEvent event) -> {
            Holder<TestEnvironmentDefinition<?>> environment = event.registerEnvironment(DhTurbo.id("default"),
                    new TestEnvironmentDefinition.AllOf(List.of()));
            for (Test test : tests) {
                Identifier id = DhTurbo.id(test.name());
                TestData<Holder<TestEnvironmentDefinition<?>>> data = new TestData<>(environment, DhTurbo.id(test.settings().template()),
                        test.settings().timeoutTicks(), 0, true, Rotation.NONE, false, 1, 1, false, 0);
                event.registerTest(id, new FunctionGameTestInstance(ResourceKey.create(Registries.TEST_FUNCTION, id), data));
            }
        });
    }

    private static List<Test> collect() {
        List<Test> tests = new ArrayList<>();
        for (Method method : ModGameTests.class.getDeclaredMethods()) {
            GameTest settings = method.getAnnotation(GameTest.class);
            if (settings == null) {
                continue;
            }
            if (!Modifier.isStatic(method.getModifiers()) || method.getParameterCount() != 1
                    || method.getParameterTypes()[0] != GameTestHelper.class) {
                throw new IllegalStateException("A game test must be static and take a GameTestHelper: " + method);
            }
            tests.add(new Test(snakeCase(method.getName()), method, settings));
        }
        return tests;
    }

    private static void run(Method method, GameTestHelper helper) {
        try {
            method.invoke(null, helper);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof RuntimeException cause) {
                throw cause;
            }
            if (e.getCause() instanceof Error cause) {
                throw cause;
            }
            throw new IllegalStateException(e.getCause());
        }
    }

    private static String snakeCase(String name) {
        return name.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase(Locale.ROOT);
    }
}

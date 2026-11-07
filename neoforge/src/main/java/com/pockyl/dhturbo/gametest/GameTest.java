package com.pockyl.dhturbo.gametest;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a static method taking a {@code GameTestHelper} as a game test. The game's own annotation ended with 1.21.5;
 * {@link GameTests} registers these in the test registries, and the attributes keep the names the annotation had, so tests
 * read the same on every Minecraft version.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface GameTest {
    /** Structure the test runs in, a path in this mod's namespace. */
    String template();

    int timeoutTicks() default 100;
}

package com.pockyl.fatamorgana.client;

/**
 * Added to Distant Horizons' {@code LodRenderSection} by {@code LodRenderSectionMixin}: how much geometry the section
 * was last built with, so the quad tree can tell an empty section from one with terrain.
 */
public interface RenderSectionContent {
    /** Quads of the last build, or -1 when the section has not been built yet. */
    int fatamorgana$quads();
}

package com.pockyl.fatamorgana.client;

import com.seibel.distanthorizons.core.render.QuadTree.LodRenderSection;
import com.seibel.distanthorizons.core.util.objects.quadTree.QuadNode;

/**
 * Added to Distant Horizons' {@code QuadTreeTickNodeHolder} by {@code QuadTreeTickNodeHolderMixin}: sections kept off
 * screen because they are empty still have to be generated, and DH only generates sections it is about to show.
 */
public interface EmptySectionQueue {
    void fatamorgana$queueEmpty(QuadNode<LodRenderSection> node);
}

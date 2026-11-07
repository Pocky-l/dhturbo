package com.pockyl.dhturbo.fabric;

import net.minecraft.client.gui.components.debug.DebugScreenEntries;
import net.minecraft.client.gui.components.debug.DebugScreenEntryStatus;
import net.minecraft.client.gui.components.debug.DebugScreenProfile;
import net.minecraft.resources.Identifier;

import com.pockyl.dhturbo.DhTurbo;
import com.pockyl.dhturbo.dh.TurboActivity;
import com.pockyl.dhturbo.platform.Platform;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;

/**
 * The debug screen entry of the generator, registered as NeoForge's RegisterDebugEntriesEvent does: an entry of its own that
 * the default profile shows with the overlay. Called from {@code mixin.DebugScreenEntriesMixin} once vanilla's entries are there.
 */
public final class StatusDebugEntry {
    private static final Identifier ID = DhTurbo.id("status");

    private StatusDebugEntry() {
    }

    /** Registers the entry and returns the profiles with the entry added to the default one. */
    public static Map<DebugScreenProfile, Map<Identifier, DebugScreenEntryStatus>> register(
            Map<DebugScreenProfile, Map<Identifier, DebugScreenEntryStatus>> profiles) {
        if (!Platform.INSTANCE.isModLoaded("distanthorizons")) {
            return profiles;
        }
        DebugScreenEntries.register(ID, (displayer, level, clientChunk, serverChunk) -> displayer.addLine(TurboActivity.debugLine()));
        Map<DebugScreenProfile, Map<Identifier, DebugScreenEntryStatus>> withEntry = new EnumMap<>(DebugScreenProfile.class);
        profiles.forEach((profile, statuses) -> {
            Map<Identifier, DebugScreenEntryStatus> copy = new HashMap<>(statuses);
            if (profile == DebugScreenProfile.DEFAULT) {
                copy.put(ID, DebugScreenEntryStatus.IN_OVERLAY);
            }
            withEntry.put(profile, Map.copyOf(copy));
        });
        return Map.copyOf(withEntry);
    }
}

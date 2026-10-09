package com.pockyl.fatamorgana.registry;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredRegister;

import com.pockyl.fatamorgana.Fatamorgana;

public final class ModItems {
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(Fatamorgana.MOD_ID);

    private ModItems() {
    }

    public static void register(IEventBus modBus) {
        ITEMS.register(modBus);
    }
}

package com.pockyl.dhturbo.client;

import com.electronwill.nightconfig.core.UnmodifiableConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.ContainerObjectSelectionList;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraftforge.common.ForgeConfigSpec;

import com.pockyl.dhturbo.Config;
import com.pockyl.dhturbo.DhTurbo;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * The in-game config editor behind the Config button of the mod list. Forge for 1.20.1 has no generic one, so this
 * screen walks the config spec: a switch for every flag and a text field for every number, named and explained by the
 * translation keys of the config (the comment when there is no translated tooltip). Changes are saved when the screen
 * is closed.
 */
public final class ConfigScreen extends Screen {
    private static final String KEY = DhTurbo.MOD_ID + ".configuration.";
    private static final int ROW_HEIGHT = 24;
    private static final int ROW_WIDTH = 310;
    private static final int CONTROL_WIDTH = 120;
    private static final int VALID_COLOR = 0xE0E0E0;
    private static final int INVALID_COLOR = 0xFF5555;

    private final Screen parent;
    private final ForgeConfigSpec spec;
    private final Function<ConfigScreen, List<Row>> rows;
    private boolean changed;

    private ConfigScreen(Screen parent, Component title, ForgeConfigSpec spec, Function<ConfigScreen, List<Row>> rows) {
        super(title);
        this.parent = parent;
        this.spec = spec;
        this.rows = rows;
    }

    /** The only page: the common config file. */
    public static Screen root(Screen parent) {
        ForgeConfigSpec spec = Config.SPEC;
        return new ConfigScreen(parent, Component.translatable(KEY + "title"), spec,
                screen -> spec.isLoaded() ? page(screen, spec, spec.getSpec(), spec.getValues()) : List.of());
    }

    /** The rows of the config: one control per value, in the order they were defined. */
    private static List<Row> page(ConfigScreen screen, ForgeConfigSpec spec, UnmodifiableConfig specs, UnmodifiableConfig values) {
        List<Row> rows = new ArrayList<>();
        for (Map.Entry<String, Object> entry : specs.valueMap().entrySet()) {
            String key = entry.getKey();
            Object value = values.valueMap().get(key);
            if (entry.getValue() instanceof ForgeConfigSpec.ValueSpec valueSpec
                    && value instanceof ForgeConfigSpec.ConfigValue<?> configValue) {
                AbstractWidget control = control(screen, key, valueSpec, configValue);
                if (control != null) {
                    String langKey = valueSpec.getTranslationKey();
                    tooltip(control, langKey, valueSpec.getComment());
                    rows.add(new Row(langKey != null ? Component.translatable(langKey) : Component.literal(key), control));
                }
            }
        }
        return rows;
    }

    private static AbstractWidget control(ConfigScreen screen, String key, ForgeConfigSpec.ValueSpec valueSpec,
            ForgeConfigSpec.ConfigValue<?> value) {
        Object current = value.get();
        Component name = valueSpec.getTranslationKey() != null ? Component.translatable(valueSpec.getTranslationKey())
                : Component.literal(key);
        if (current instanceof Boolean flag) {
            return CycleButton.onOffBuilder(flag)
                    .displayOnlyValue()
                    .create(0, 0, CONTROL_WIDTH, 20, name, (button, choice) -> screen.set(value, choice));
        }
        if (current instanceof Integer) {
            return numberBox(screen, name, valueSpec, value, current, Integer::valueOf);
        }
        if (current instanceof Double) {
            return numberBox(screen, name, valueSpec, value, current, Double::valueOf);
        }
        return null;
    }

    /** A text field that takes the number once it parses and lies in the allowed range; until then it shows red. */
    private static EditBox numberBox(ConfigScreen screen, Component name, ForgeConfigSpec.ValueSpec valueSpec,
            ForgeConfigSpec.ConfigValue<?> value, Object current, Function<String, Object> parser) {
        EditBox box = new EditBox(Minecraft.getInstance().font, 0, 0, CONTROL_WIDTH, 20, name);
        box.setMaxLength(32);
        box.setValue(String.valueOf(current));
        box.setResponder(text -> {
            Object parsed;
            try {
                parsed = parser.apply(text.trim());
            } catch (NumberFormatException e) {
                parsed = null;
            }
            boolean valid = parsed != null && !(parsed instanceof Double number && !Double.isFinite(number)) && valueSpec.test(parsed);
            box.setTextColor(valid ? VALID_COLOR : INVALID_COLOR);
            if (valid) {
                screen.set(value, parsed);
            }
        });
        return box;
    }

    /** The translated tooltip of the value, or its comment. */
    private static void tooltip(AbstractWidget widget, String langKey, String comment) {
        if (langKey != null && I18n.exists(langKey + ".tooltip")) {
            widget.setTooltip(Tooltip.create(Component.translatable(langKey + ".tooltip")));
        } else if (comment != null && !comment.isBlank()) {
            widget.setTooltip(Tooltip.create(Component.literal(comment.trim())));
        }
    }

    @SuppressWarnings("unchecked")
    private void set(ForgeConfigSpec.ConfigValue<?> value, Object newValue) {
        ((ForgeConfigSpec.ConfigValue<Object>) value).set(newValue);
        changed = true;
    }

    @Override
    protected void init() {
        RowList list = new RowList(minecraft, width, height, 32, height - 32);
        for (Row row : rows.apply(this)) {
            list.add(row);
        }
        addRenderableWidget(list);
        addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, button -> onClose())
                .bounds(width / 2 - 100, height - 27, 200, 20)
                .build());
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);
        super.render(graphics, mouseX, mouseY, partialTick);
        graphics.drawCenteredString(font, title, width / 2, 12, 0xFFFFFF);
    }

    @Override
    public void onClose() {
        if (changed && spec.isLoaded()) {
            spec.save();
            changed = false;
        }
        minecraft.setScreen(parent);
    }

    private static final class RowList extends ContainerObjectSelectionList<Row> {
        RowList(Minecraft minecraft, int width, int height, int top, int bottom) {
            super(minecraft, width, height, top, bottom, ROW_HEIGHT);
        }

        void add(Row row) {
            addEntry(row);
        }

        @Override
        public int getRowWidth() {
            return ROW_WIDTH;
        }

        @Override
        protected int getScrollbarPosition() {
            return width / 2 + ROW_WIDTH / 2 + 10;
        }
    }

    /** A labelled control. */
    private static final class Row extends ContainerObjectSelectionList.Entry<Row> {
        private final Component label;
        private final AbstractWidget widget;

        Row(Component label, AbstractWidget widget) {
            this.label = label;
            this.widget = widget;
        }

        @Override
        public List<? extends GuiEventListener> children() {
            return List.of(widget);
        }

        @Override
        public List<? extends NarratableEntry> narratables() {
            return List.of(widget);
        }

        @Override
        public void render(GuiGraphics graphics, int index, int top, int left, int width, int height, int mouseX, int mouseY,
                boolean hovering, float partialTick) {
            graphics.drawString(Minecraft.getInstance().font, label, left, top + (20 - 8) / 2, 0xFFFFFF);
            widget.setX(left + width - CONTROL_WIDTH);
            widget.setY(top);
            widget.render(graphics, mouseX, mouseY, partialTick);
        }
    }
}

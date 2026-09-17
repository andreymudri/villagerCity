package dev.andreymudri.villagercity.client;

import dev.andreymudri.villagercity.storehouse.StorehouseActionPayload;
import dev.andreymudri.villagercity.storehouse.StorehouseEntry;
import dev.andreymudri.villagercity.storehouse.StorehouseMenu;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import javax.annotation.Nullable;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;

/** The storehouse screen: a scrollable nine-by-six grid of stored entries above the player's inventory. */
public class StorehouseScreen extends AbstractContainerScreen<StorehouseMenu> {
    private static final ResourceLocation BACKGROUND = ResourceLocation.withDefaultNamespace("textures/gui/container/generic_54.png");
    private static final ResourceLocation SCROLLER = ResourceLocation.withDefaultNamespace("container/creative_inventory/scroller");
    private static final ResourceLocation SCROLLER_DISABLED = ResourceLocation.withDefaultNamespace("container/creative_inventory/scroller_disabled");
    private static final int SCROLLBAR_X = 178;
    private static final int SCROLLBAR_WIDTH = 14;

    private int scrollRow;
    private boolean draggingScrollbar;
    /** A press on the grid or scrollbar, whose release must not reach the slot handling (it would drop or place the cursor stack). */
    private boolean ownPress;

    public StorehouseScreen(StorehouseMenu menu, Inventory playerInventory, Component title) {
        super(menu, playerInventory, title);
        this.imageHeight = 114 + StorehouseMenu.ROWS * 18;
        this.imageWidth = 176;
        this.inventoryLabelY = this.imageHeight - 94;
    }

    /** Entries sorted by item id, then name, so they keep their place while counts change. */
    private List<StorehouseEntry> sortedEntries() {
        List<StorehouseEntry> entries = new ArrayList<>(menu.clientEntries());
        entries.sort(Comparator.<StorehouseEntry, String>comparing(entry -> BuiltInRegistries.ITEM.getKey(entry.prototype().getItem()).toString())
                .thenComparing(entry -> entry.prototype().getHoverName().getString()));
        return entries;
    }

    private int maxScrollRow(int entryCount) {
        int rows = (entryCount + StorehouseMenu.COLUMNS - 1) / StorehouseMenu.COLUMNS;
        return Math.max(0, rows - StorehouseMenu.ROWS);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        StorehouseEntry hovered = entryAt(sortedEntries(), mouseX, mouseY);
        if (hovered != null && menu.getCarried().isEmpty()) {
            List<Component> lines = new ArrayList<>(getTooltipFromContainerItem(hovered.prototype()));
            lines.add(Component.translatable("container.villagercity.storehouse.stored", String.format("%,d", hovered.count())).withStyle(ChatFormatting.GRAY));
            graphics.renderTooltip(font, lines, hovered.prototype().getTooltipImage(), hovered.prototype(), mouseX, mouseY);
        }
        renderTooltip(graphics, mouseX, mouseY);
    }

    /** The stored entries are drawn with the background, so the stack on the cursor stays on top of them. */
    private void renderEntries(GuiGraphics graphics, int mouseX, int mouseY) {
        List<StorehouseEntry> entries = sortedEntries();
        scrollRow = Mth.clamp(scrollRow, 0, maxScrollRow(entries.size()));
        for (int cell = 0; cell < StorehouseMenu.COLUMNS * StorehouseMenu.ROWS; cell++) {
            int index = scrollRow * StorehouseMenu.COLUMNS + cell;
            if (index >= entries.size()) {
                break;
            }
            StorehouseEntry entry = entries.get(index);
            int x = leftPos + StorehouseMenu.GRID_X + cell % StorehouseMenu.COLUMNS * 18;
            int y = topPos + StorehouseMenu.GRID_Y + cell / StorehouseMenu.COLUMNS * 18;
            graphics.renderItem(entry.prototype(), x, y);
            graphics.renderItemDecorations(font, entry.prototype(), x, y, abbreviate(entry.count()));
        }
        if (inGrid(mouseX, mouseY)) {
            int x = leftPos + StorehouseMenu.GRID_X + (mouseX - leftPos - StorehouseMenu.GRID_X) / 18 * 18;
            int y = topPos + StorehouseMenu.GRID_Y + (mouseY - topPos - StorehouseMenu.GRID_Y) / 18 * 18;
            renderSlotHighlight(graphics, x, y, 0);
        }
    }

    @Override
    protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        graphics.blit(BACKGROUND, leftPos, topPos, 0, 0, imageWidth, StorehouseMenu.ROWS * 18 + 17);
        graphics.blit(BACKGROUND, leftPos, topPos + StorehouseMenu.ROWS * 18 + 17, 0, 126, imageWidth, 96);
        int trackX = leftPos + SCROLLBAR_X;
        int trackTop = topPos + StorehouseMenu.GRID_Y;
        int trackHeight = StorehouseMenu.ROWS * 18;
        graphics.fill(trackX, trackTop - 1, trackX + SCROLLBAR_WIDTH, trackTop + trackHeight + 1, 0xff373737);
        graphics.fill(trackX + 1, trackTop, trackX + SCROLLBAR_WIDTH - 1, trackTop + trackHeight, 0xff8b8b8b);
        int maxScroll = maxScrollRow(menu.clientEntries().size());
        int knobY = trackTop + (maxScroll == 0 ? 0 : (trackHeight - 15) * scrollRow / maxScroll);
        graphics.blitSprite(maxScroll == 0 ? SCROLLER_DISABLED : SCROLLER, trackX + 1, knobY, 12, 15);
        renderEntries(graphics, mouseX, mouseY);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (inScrollbar(mouseX, mouseY)) {
            draggingScrollbar = true;
            ownPress = true;
            scrollTo(mouseY);
            return true;
        }
        if (inGrid(mouseX, mouseY) && (button == 0 || button == 1)) {
            StorehouseEntry entry = entryAt(sortedEntries(), mouseX, mouseY);
            ItemStack prototype = entry == null ? ItemStack.EMPTY : entry.prototype();
            StorehouseMenu.Button action = button == 1 ? StorehouseMenu.Button.RIGHT
                    : hasShiftDown() ? StorehouseMenu.Button.SHIFT : StorehouseMenu.Button.LEFT;
            ownPress = true;
            if (entry != null || !menu.getCarried().isEmpty()) {
                PacketDistributor.sendToServer(new StorehouseActionPayload(menu.containerId, prototype, action));
            }
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (draggingScrollbar) {
            scrollTo(mouseY);
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        draggingScrollbar = false;
        if (ownPress) {
            ownPress = false;
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        scrollRow = Mth.clamp(scrollRow - (int) Math.signum(scrollY), 0, maxScrollRow(menu.clientEntries().size()));
        return true;
    }

    @Override
    protected boolean hasClickedOutside(double mouseX, double mouseY, int left, int top, int button) {
        return super.hasClickedOutside(mouseX, mouseY, left, top, button) && !inScrollbar(mouseX, mouseY);
    }

    private void scrollTo(double mouseY) {
        int trackTop = topPos + StorehouseMenu.GRID_Y;
        float fraction = (float) ((mouseY - trackTop - 7.5) / (StorehouseMenu.ROWS * 18 - 15));
        int maxScroll = maxScrollRow(menu.clientEntries().size());
        scrollRow = Mth.clamp(Math.round(Mth.clamp(fraction, 0f, 1f) * maxScroll), 0, maxScroll);
    }

    private boolean inGrid(double mouseX, double mouseY) {
        double x = mouseX - leftPos - StorehouseMenu.GRID_X;
        double y = mouseY - topPos - StorehouseMenu.GRID_Y;
        return x >= 0 && y >= 0 && x < StorehouseMenu.COLUMNS * 18 && y < StorehouseMenu.ROWS * 18;
    }

    private boolean inScrollbar(double mouseX, double mouseY) {
        double x = mouseX - leftPos - SCROLLBAR_X;
        double y = mouseY - topPos - StorehouseMenu.GRID_Y;
        return x >= 0 && x < SCROLLBAR_WIDTH && y >= 0 && y < StorehouseMenu.ROWS * 18;
    }

    private @Nullable StorehouseEntry entryAt(List<StorehouseEntry> entries, double mouseX, double mouseY) {
        if (!inGrid(mouseX, mouseY)) {
            return null;
        }
        int column = (int) (mouseX - leftPos - StorehouseMenu.GRID_X) / 18;
        int row = (int) (mouseY - topPos - StorehouseMenu.GRID_Y) / 18;
        int index = (scrollRow + row) * StorehouseMenu.COLUMNS + column;
        return index < entries.size() ? entries.get(index) : null;
    }

    /** 999, 1.2k, 12k, 1.2M, 3.4B. */
    static String abbreviate(long count) {
        if (count < 1000) {
            return Long.toString(count);
        }
        String[] units = {"k", "M", "B", "T"};
        double value = count;
        int unit = -1;
        while (value >= 1000 && unit < units.length - 1) {
            value /= 1000;
            unit++;
        }
        return (value < 10 ? String.format("%.1f", Math.floor(value * 10) / 10) : Long.toString((long) value)) + units[unit];
    }
}

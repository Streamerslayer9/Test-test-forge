package com.example.gulp;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.inventory.ContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** Client-only: adds a Back button to the item storage screen so you can return to the stomach screen. */
@Mod.EventBusSubscriber(modid = Gulp.ID, value = Dist.CLIENT)
public final class ClientScreenEvents {
    private ClientScreenEvents() { }

    /**
     * In the inventory screen, pressing the "swallow hovered item" key (default V) while the mouse is over an item
     * swallows that stack into your item storage; hold Ctrl to swallow just one. (Swallowing dropped items in the world
     * with V still works as before.)
     */
    @SubscribeEvent
    public static void onInventoryKey(ScreenEvent.KeyPressed.Pre e) {
        if (!(e.getScreen() instanceof InventoryScreen screen)) return;
        if (!ClientModEvents.INVENTORY_SWALLOW_KEY.isActiveAndMatches(InputConstants.getKey(e.getKeyCode(), e.getScanCode()))) return;
        Slot slot = screen.getSlotUnderMouse();
        // Only your own inventory and hotbar (not armor, the offhand or the crafting grid).
        if (slot == null || !slot.hasItem() || !(slot.container instanceof Inventory) || slot.getSlotIndex() >= 36) return;
        Net.send(9, slot.index | (Screen.hasControlDown() ? 1 << 16 : 0));
        e.setCanceled(true);
    }

    @SubscribeEvent
    public static void onScreenInit(ScreenEvent.Init.Post e) {
        // The item storage is a normal chest-style screen with a special title.
        if (!(e.getScreen() instanceof ContainerScreen screen)) return;
        if (!Gulp.STORAGE_TITLE.equals(screen.getTitle().getString())) return;

        int width = 52;
        int x = screen.getGuiLeft() - width - 4;             // just left of the storage window
        if (x < 2) x = screen.getGuiLeft() + screen.getXSize() + 4; // no room on the left: put it on the right

        Button back = Button.builder(Component.literal("Back"), btn -> {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player == null) return;
            mc.player.closeContainer();          // tells the server the storage is closed (anything on your cursor goes back to you)
            mc.setScreen(new StomachScreen());   // and back to the stomach screen
        }).bounds(x, screen.getGuiTop(), width, 20).build();
        e.addListener(back);
    }
}

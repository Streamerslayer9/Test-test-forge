package com.example.gulp;

import net.minecraft.core.RegistryAccess;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.ItemTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.food.FoodData;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.CustomRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraftforge.items.ItemHandlerHelper;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;

/**
 * Things that happen to the ITEMS in the stomach's item storage, on the server tick:
 *
 *  Feature B, Nutrient Absorption perk (any mode): food in storage is eaten over time. Each rank makes it faster and
 *  more filling. It only eats when you actually have room to be fed, and it never wastes food on a full bar.
 *
 *  Feature A, deconstruction (hard mode only, like mobs): damageable gear (tools, weapons, armor, bows, shields ...)
 *  is broken down into the ingredients of its crafting recipe (iron armor -> iron ingots). The more worn the item,
 *  the fewer parts you get back. Gear with no crafting recipe (netherite, modded) is left alone, never deleted.
 *  Items in the data tag gulp:stomach_trash (cobblestone, dirt ...) simply dissolve. Nothing else is ever destroyed.
 */
public final class StomachItems {
    /** Items in this tag dissolve in hard mode. Edit data/gulp/tags/items/stomach_trash.json or add to it in a datapack. */
    public static final TagKey<Item> TRASH = ItemTags.create(new ResourceLocation(Gulp.ID, "stomach_trash"));

    /** Ticks between deconstruction passes (20 per second). One gear item is broken down per pass. */
    private static final int DECON_INTERVAL = 60;
    /** How many trash items dissolve per pass when there is no gear to break down. */
    private static final int TRASH_PER_PASS = 4;

    // Recipe lookups are cached per RecipeManager (a /reload makes a new one, which clears the cache).
    private static final Map<Item, List<ItemStack>> PARTS = new HashMap<>();
    private static RecipeManager cachedFor;

    private StomachItems() { }

    /** Called every tick for every player from StomachLogic.tickPlayer. */
    public static void tick(ServerPlayer p, StomachManager mgr, Stomach s, boolean hardNow) {
        if (!GulpServerConfig.ITEM_STORAGE_ENABLED.get()) return;
        if (!p.isAlive() || p.isSpectator() || PlayerHolding.isHeld(p)) return;
        // While any container is open (including the storage screen itself) leave the storage alone,
        // otherwise the open screen and the saved storage would disagree.
        if (p.containerMenu != p.inventoryMenu) return;

        boolean changed = false;

        int foodRank = s.rank(Perk.NUTRIENT);
        if (foodRank > 0 && GulpServerConfig.FOOD_DIGESTION_ENABLED.get()) {
            int interval = Math.max(10, 50 - 4 * foodRank); // one item every 2.3s at rank 1, every 0.5s at rank 10
            if (p.tickCount % interval == 0) changed |= eatOne(p, s, foodRank);
        }

        if (hardNow && GulpServerConfig.ITEM_DIGESTION_ENABLED.get() && p.tickCount % DECON_INTERVAL == 0) {
            changed |= breakDownOne(p, s);
        }

        if (changed) {
            mgr.setDirty();
            StomachLogic.sync(p, s); // also updates the item-fill belly (CPM sliders)
        }
    }

    // ------------------------------------------------------------------ Feature B: food

    /** Share of a food's nutrition you get back: 46% at rank 1, 100% from rank 10. */
    public static float efficiency(int rank) {
        return Math.min(1f, 0.4f + 0.06f * rank);
    }

    /** @return true if one food item was eaten */
    private static boolean eatOne(ServerPlayer p, Stomach s, int rank) {
        FoodData food = p.getFoodData();
        if (food.getFoodLevel() >= 20) return false;
        int slots = s.storageSlots();
        for (int i = 0; i < slots; i++) {
            ItemStack stack = s.storage.get(i);
            if (stack.isEmpty()) continue;
            FoodProperties fp = stack.getFoodProperties(p);
            if (fp == null) continue;
            int nutrition = Math.max(1, Math.round(fp.getNutrition() * efficiency(rank)));
            // Don't waste food: skip anything that would mostly overflow the hunger bar (try a smaller one instead).
            if (food.getFoodLevel() > 20 - Math.max(1, nutrition / 2)) continue;

            ItemStack remainder = stack.hasCraftingRemainingItem() ? stack.getCraftingRemainingItem() : ItemStack.EMPTY;
            stack.shrink(1);
            if (stack.isEmpty()) s.storage.set(i, ItemStack.EMPTY);
            food.eat(nutrition, fp.getSaturationModifier());
            if (!remainder.isEmpty()) stash(p, s, remainder); // a bowl, a bottle ...
            // Perk XP: half the nutrition eaten (the server's xpMultiplier applies).
            StomachLogic.gainXp(p, s, nutrition * 0.5f);
            return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ Feature A: deconstruction

    /** @return true if something was broken down or dissolved */
    private static boolean breakDownOne(ServerPlayer p, Stomach s) {
        int slots = s.storageSlots();
        MinecraftServer server = p.getServer();

        // 1) Gear first: one item per pass.
        for (int i = 0; i < slots; i++) {
            ItemStack stack = s.storage.get(i);
            if (stack.isEmpty() || !stack.isDamageableItem()) continue;
            if (stack.isEnchanted() && !GulpServerConfig.DECONSTRUCT_ENCHANTED.get()) continue;
            List<ItemStack> parts = partsOf(server, stack.getItem());
            if (parts == null) continue; // no crafting recipe: keep it, never delete it

            double condition = 1.0 - stack.getDamageValue() / (double) Math.max(1, stack.getMaxDamage());
            Component name = stack.getHoverName();
            stack.shrink(1);
            if (stack.isEmpty()) s.storage.set(i, ItemStack.EMPTY); // free the slot BEFORE inserting the parts
            for (ItemStack part : parts) {
                // Worn gear gives back less: 100% of the parts when new, about half when nearly broken.
                int count = Math.max(1, (int) Math.round(part.getCount() * (0.5 + 0.5 * condition)));
                stash(p, s, new ItemStack(part.getItem(), count));
            }
            StomachLogic.say(p, Component.literal("Broke down ").append(name));
            return true;
        }

        // 2) Otherwise let trash dissolve.
        if (!GulpServerConfig.TRASH_DISSOLVE_ENABLED.get()) return false;
        for (int i = 0; i < slots; i++) {
            ItemStack stack = s.storage.get(i);
            if (stack.isEmpty() || !stack.is(TRASH) || stack.hasTag()) continue;
            stack.shrink(TRASH_PER_PASS);
            if (stack.isEmpty()) s.storage.set(i, ItemStack.EMPTY);
            return true;
        }
        return false;
    }

    /** Puts a stack in the item storage; whatever doesn't fit goes to the player's inventory (or their feet). */
    private static void stash(ServerPlayer p, Stomach s, ItemStack stack) {
        ItemStack left = s.insertItem(stack);
        if (!left.isEmpty()) ItemHandlerHelper.giveItemToPlayer(p, left);
    }

    /**
     * What a piece of gear is made of: the ingredients of its first normal crafting recipe, or null if it has none.
     * One level only (a chestplate gives ingots, not nuggets), and the result is cached.
     */
    private static List<ItemStack> partsOf(MinecraftServer server, Item item) {
        RecipeManager rm = server.getRecipeManager();
        if (rm != cachedFor) {
            PARTS.clear();
            cachedFor = rm;
        }
        if (PARTS.containsKey(item)) return PARTS.get(item);

        List<ItemStack> result = null;
        RegistryAccess access = server.registryAccess();
        for (CraftingRecipe recipe : rm.getAllRecipesFor(RecipeType.CRAFTING)) {
            if (recipe instanceof CustomRecipe) continue; // dyeing, repairing, fireworks ... not real "recipes"
            ItemStack out = recipe.getResultItem(access);
            if (out.isEmpty() || out.getItem() != item) continue;

            Map<Item, Integer> counts = new LinkedHashMap<>();
            boolean selfReference = false;
            for (Ingredient ingredient : recipe.getIngredients()) {
                if (ingredient.isEmpty()) continue;
                ItemStack[] options = ingredient.getItems();
                if (options.length == 0) continue;
                Item pick = options[0].getItem();
                if (pick == item) selfReference = true;
                counts.merge(pick, 1, Integer::sum);
            }
            if (selfReference || counts.isEmpty()) continue;

            int perCraft = Math.max(1, out.getCount());
            List<ItemStack> parts = new ArrayList<>();
            for (Map.Entry<Item, Integer> e : counts.entrySet()) {
                parts.add(new ItemStack(e.getKey(), Math.max(1, e.getValue() / perCraft)));
            }
            result = parts;
            break;
        }
        PARTS.put(item, result);
        return result;
    }
}

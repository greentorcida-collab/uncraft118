package com.uncraft;

import java.util.LinkedHashMap;
import java.util.Map;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.recipe.Ingredient;
import net.minecraft.recipe.Recipe;
import net.minecraft.recipe.RecipeType;
import net.minecraft.recipe.ShapedRecipe;
import net.minecraft.recipe.ShapelessRecipe;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.LiteralText;
import net.minecraft.util.Identifier;

public class Uncraft implements ModInitializer {
    public static final Identifier UNCRAFT_PACKET = new Identifier("uncraft", "reverse");

    private record Plan(int outputCount, Map<Item, Integer> ingredients) {}

    @Override
    public void onInitialize() {
        ServerPlayNetworking.registerGlobalReceiver(UNCRAFT_PACKET,
            (server, player, handler, buf, responseSender) ->
                server.execute(() -> reverse(server, player)));
    }

    private static void message(ServerPlayerEntity player, String text) {
        player.sendMessage(new LiteralText(text), true);
    }

    // Cari resep crafting yang menghasilkan item ini, pilih yang paling "ketat" supaya tidak ada duplikasi
    private static Plan findPlan(MinecraftServer server, Item item) {
        Plan best = null;
        double bestRatio = Double.MAX_VALUE;

        for (Recipe<?> recipe : server.getRecipeManager().values()) {
            if (recipe.getType() != RecipeType.CRAFTING) {
                continue;
            }
            if (!(recipe instanceof ShapedRecipe) && !(recipe instanceof ShapelessRecipe)) {
                continue;
            }
            ItemStack output = recipe.getOutput();
            if (output.isEmpty() || output.getItem() != item) {
                continue;
            }

            Map<Item, Integer> ingredients = new LinkedHashMap<>();
            boolean valid = true;
            int total = 0;
            for (Ingredient ingredient : recipe.getIngredients()) {
                if (ingredient.isEmpty()) {
                    continue;
                }
                ItemStack[] options = ingredient.getMatchingStacks();
                if (options.length == 0) {
                    valid = false;
                    break;
                }
                Item ingredientItem = options[0].getItem();
                // Lewati resep yang bahannya meninggalkan sisa (ember susu, dll) atau memakai item yang sama
                if (ingredientItem.hasRecipeRemainder() || ingredientItem == item) {
                    valid = false;
                    break;
                }
                ingredients.merge(ingredientItem, 1, Integer::sum);
                total++;
            }
            if (!valid || total == 0) {
                continue;
            }

            double ratio = (double) total / output.getCount();
            if (ratio < bestRatio) {
                bestRatio = ratio;
                best = new Plan(output.getCount(), ingredients);
            }
        }
        return best;
    }

    private static void give(ServerPlayerEntity player, Item item, int amount) {
        int max = item.getMaxCount();
        while (amount > 0) {
            int n = Math.min(amount, max);
            player.getInventory().offerOrDrop(new ItemStack(item, n));
            amount -= n;
        }
    }

    private static void reverse(MinecraftServer server, ServerPlayerEntity player) {
        ScreenHandler screen = player.currentScreenHandler;
        ItemStack cursor = screen.getCursorStack();
        if (cursor.isEmpty()) {
            message(player, "Ambil blok dulu (tap sampai menempel di kursor), lalu tap R");
            return;
        }

        Item item = cursor.getItem();
        if (!(item instanceof BlockItem)) {
            message(player, "Hanya blok yang bisa dibalik");
            return;
        }

        Plan plan = findPlan(server, item);
        if (plan == null) {
            message(player, "Blok ini tidak punya resep untuk dibalik");
            return;
        }

        int count = cursor.getCount();
        int n = plan.outputCount();
        int crafts = count / n;
        int remainder = count % n;

        boolean remainderYields = false;
        for (Map.Entry<Item, Integer> entry : plan.ingredients().entrySet()) {
            if ((remainder * entry.getValue()) / n > 0) {
                remainderYields = true;
            }
        }

        Map<Item, Integer> results = new LinkedHashMap<>();
        for (Map.Entry<Item, Integer> entry : plan.ingredients().entrySet()) {
            int amount = crafts * entry.getValue();
            if (remainderYields) {
                amount += (remainder * entry.getValue()) / n;
            }
            if (amount > 0) {
                results.merge(entry.getKey(), amount, Integer::sum);
            }
        }

        if (results.isEmpty()) {
            message(player, "Jumlah terlalu sedikit, tambah blok yang sama lalu coba lagi");
            return;
        }

        int converted = remainderYields ? count : crafts * n;
        int leftover = count - converted;

        StringBuilder summary = new StringBuilder("Dibalik jadi: ");
        boolean first = true;
        for (Map.Entry<Item, Integer> entry : results.entrySet()) {
            give(player, entry.getKey(), entry.getValue());
            if (!first) {
                summary.append(", ");
            }
            summary.append(entry.getValue()).append("x ").append(entry.getKey().getName().getString());
            first = false;
        }

        if (leftover > 0) {
            ItemStack rest = cursor.copy();
            rest.setCount(leftover);
            screen.setCursorStack(rest);
        } else {
            screen.setCursorStack(ItemStack.EMPTY);
        }
        message(player, summary.toString());
    }
}

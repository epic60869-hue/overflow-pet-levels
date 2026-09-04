package com.nopo

import com.google.gson.Gson
import com.google.gson.JsonObject
import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.item.v1.ItemTooltipCallback
import net.minecraft.ChatFormatting
import net.minecraft.core.component.DataComponents
import net.minecraft.network.chat.Component
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.item.TooltipFlag

object OverflowPetLevels : ClientModInitializer {

    override fun onInitializeClient() {
        // ItemTooltipCallback is registered in init below.
    }

    init {
        ItemTooltipCallback.EVENT.register(ItemTooltipCallback { itemStack: ItemStack, tooltipContext: Item.TooltipContext, tooltipType: TooltipFlag?, list: MutableList<Component> ->
            if (itemStack.item != Items.PLAYER_HEAD) return@ItemTooltipCallback

            val nbt = itemStack.get(DataComponents.CUSTOM_DATA)?.copyTag()
                ?: return@ItemTooltipCallback

            if (!nbt.contains("petInfo")) return@ItemTooltipCallback

            val petInfo = nbt.get("petInfo")?.asString()?.orElse(null)
                ?: return@ItemTooltipCallback

            val json: JsonObject = try {
                Gson().fromJson(petInfo, JsonObject::class.java)
            } catch (_: Exception) {
                return@ItemTooltipCallback
            }

            if (!json.has("exp")) return@ItemTooltipCallback

            val xp = json.get("exp")?.asFloat ?: return@ItemTooltipCallback

            for ((index, text) in list.withIndex()) {
                if (text.string.contains("MAX LEVEL")) {
                    val newText = Component.literal("MAX LEVEL")
                        .withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD)
                    val reset = Component.literal("§r")
                        .withStyle(ChatFormatting.RESET)

                    val noBold = newText.style.withBold(false)
                    val xpText = Component.literal("${calcLevel(xp)}✦")
                        .setStyle(noBold)
                        .withStyle(ChatFormatting.GOLD)
                    val openBracket = Component.literal(" [")
                        .setStyle(noBold)
                        .withStyle(ChatFormatting.GRAY)
                    val closeBracket = Component.literal("]")
                        .setStyle(noBold)
                        .withStyle(ChatFormatting.GRAY)

                    list[index] = newText
                        .append(reset)
                        .append(openBracket)
                        .append(xpText)
                        .append(closeBracket)
                    return@ItemTooltipCallback
                }
            }
        })
    }

    private fun calcLevel(xp: Float): Int {
        var exp = xp
        var level = 0

        while (exp > 0) {
            val requiredXp = if (listOfXp.size > level) {
                listOfXp[level]
            } else {
                1_886_700
            }

            exp -= requiredXp
            level++
        }

        if (level < 1) level = 1
        return level
    }

    private val listOfXp = listOf(
        660, 730, 800, 880, 960, 1050, 1150, 1260, 1380, 1510,
        1650, 1800, 1960, 2130, 2310, 2500, 2700, 2920, 3160, 3420,
        3700, 4000, 4350, 4750, 5200, 5700, 6300, 7000, 7800, 8700,
        9700, 10800, 12000, 13300, 14700, 16200, 17800, 19500, 21300, 23200,
        25200, 27400, 29800, 32400, 35200, 38200, 41400, 44800, 48400, 52200,
        56200, 60400, 64800, 69400, 74200, 79200, 84700, 90700, 97200, 104200,
        111700, 119700, 128200, 137200, 146700, 156700, 167700, 179700, 192700, 206700,
        221700, 237700, 254700, 272700, 291700, 311700, 333700, 357700, 383700, 411700,
        441700, 476700, 516700, 561700, 611700, 666700, 726700, 791700, 861700, 936700,
        1016700, 1101700, 1191700, 1286700, 1386700, 1496700, 1616700, 1746700, 1886700,
        0, 5555,
    )
}

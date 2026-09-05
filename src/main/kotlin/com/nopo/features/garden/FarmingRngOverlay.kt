package com.nopo.features.garden

import com.google.gson.JsonParser
import com.nopo.NopoMod
import com.nopo.config.PositionConfig
import com.nopo.events.ChatEvent
import com.nopo.events.GuiRendering
import com.nopo.events.TickEvent
import com.nopo.module.ConfigData
import com.nopo.module.FeatureModule
import com.nopo.utils.HypixelUtils
import com.nopo.utils.IslandType
import com.nopo.utils.Utils.append
import com.nopo.utils.Utils.appendWithColor
import com.nopo.utils.Utils.cleanColor
import com.nopo.utils.Utils.componentBuilder
import net.minecraft.ChatFormatting
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.network.chat.Component
import java.net.URI
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

object FarmingRngOverlay : FeatureModule(
    "farmingRngOverlay",
    NopoMod.config.farmingRngOverlay,
    ConfigData(
        Component.literal("Farming RNG Overlay"),
        componentBuilder {
            append("Shows farming RNG drops with quantity, item name and value")
        }
    )
), ChatEvent, GuiRendering, TickEvent {

    private data class Drop(
        val amount: Int,
        val name: String,
        val color: ChatFormatting,
        val value: Long?
    )

    private var activeDrop: Drop? = null
    private var shownUntil = 0L
    private var animationStart = 0L
    private val prices = ConcurrentHashMap<String, Long>()
    private val pending = ConcurrentHashMap.newKeySet<String>()

    private val farmingDrops = setOf(
        // Harvest Feast / Rare Crops
        "Cornucopia", "Carrot Zest", "Deepfries", "Aggourdian", "Cane Knot", "Melon Juice",
        "Cactus Flower", "Designer Coffee Beans", "Feastfungus", "Botroot", "Salted Sunflower Seeds",
        "Crystalized Moonlight", "Floral Gelatin",
        // Older farming RNG drops
        "Cropie", "Squash", "Fermento", "Burrowing Spores", "Overgrown Grass", "Green Bandana",
        "Dedication IV", "Dedication 4", "Flowering Bouquet", "Rooted Spores", "Fruit Bowl",
        "Atmospheric Filter", "Beady Eyes", "Clipped Wings", "Mantid Claw", "Wriggling Larva",
        "Locust Larva", "Squeaky Toy", "Squeaky Mousemat", "Vermin Vaporizer", "Synthesis",
        "Evergreen", "Quickdraw", "Hypercharge", "Fire in a Bottle", "Iridium",
        "Overclocker 3000", "Rabbit Hat", "Lucky Clover Core", "Bulky Stone",
        "Turbo-Wheat V", "Turbo-Carrot V", "Turbo-Potato V", "Turbo-Pumpkin V", "Turbo-Melon V",
        "Turbo-Cocoa V", "Turbo-Cactus V", "Turbo-Mushrooms V", "Turbo-Cane V", "Turbo-Warts V",
        "Cultivating X", "Cultivating 10"
    )

    // Supports both the older "RARE DROP! ..." messages and the newer
    // Harvest Feast format, e.g. "RARE CROP! Crystalized Moonlight (+152)".
    private val rngMessageRegex = Regex(
        "(?i)^(?:.*?\u00a7.)?(?:RARE CROP!|(?:VERY |CRAZY |PRAY TO RNGESUS |RNGESUS INCARNATE )?RARE DROP!?|VERY RARE DROP!?|CRAZY RARE DROP!?|PRAY TO RNGESUS!?|RNGESUS INCARNATE!?)[ ]*(?<item>.+?)\\s*(?:\\(\\+[^)]*\\))?[! ]*$"
    )
    private val olderDropRegex = Regex(
        "(?i)(?:you (?:found|dropped|got)|you received|drop(?:ped)?[: ])\\s*(?:an? |some )?(?<item>.+?)(?:!|$)"
    )
    private val quantityRegex = Regex("(?i)^(?:x(?<x1>\\d+)\\s+|(?<x2>\\d+)x\\s+)(?<item>.+)$")

    fun getConfig() = config as PositionConfig

    override fun onChat(message: Component, actionBar: Boolean) {
        if (actionBar || !config.enabled || !HypixelUtils.onSkyblock()) return
        if (!IslandType.GARDEN.isActive()) return

        val raw = message.string.cleanColor().trim()
        val parsed = parseDrop(raw) ?: return
        val key = normalize(parsed.second)
        val cachedValue = prices[key]

        activeDrop = Drop(parsed.first, parsed.second, rarityColor(message), cachedValue)
        shownUntil = System.currentTimeMillis() + 5000L
        animationStart = System.currentTimeMillis()

        if (cachedValue == null) requestPrice(parsed.second)
    }

    private fun parseDrop(raw: String): Pair<Int, String>? {
        val match = rngMessageRegex.find(raw)
        var item: String

        if (match != null) {
            item = match.groups["item"]?.value?.trim() ?: return null
        } else {
            // Keep support for messages such as "RARE DROP! You found 2x Cropie!".
            val rarity = raw.contains("RARE DROP", ignoreCase = true) ||
                raw.contains("RNGESUS", ignoreCase = true)
            if (!rarity) return null

            val older = olderDropRegex.find(raw) ?: return null
            item = older.groups["item"]?.value?.trim() ?: return null
        }

        // The new RARE CROP message appends a farming-fortune amount such as
        // "(+152)". It is not part of the item name/value.
        item = item.replace(Regex("\\s*\\(\\+[^)]*\\)\\s*$"), "").trim()
            .removeSuffix("!")
            .trim()

        var amount = 1
        quantityRegex.matchEntire(item)?.let { quantity ->
            amount = quantity.groups["x1"]?.value?.toIntOrNull()
                ?: quantity.groups["x2"]?.value?.toIntOrNull()
                ?: 1
            item = quantity.groups["item"]?.value?.trim() ?: item
        }

        val known = farmingDrops.firstOrNull { item.equals(it, ignoreCase = true) }
            ?: farmingDrops.firstOrNull { item.contains(it, ignoreCase = true) }
            ?: return null

        return amount to known
    }

    private fun rarityColor(message: Component): ChatFormatting {
        val text = message.toString()
        return when {
            text.contains("RARE CROP", ignoreCase = true) -> ChatFormatting.BLUE
            text.contains("CRAZY RARE", ignoreCase = true) -> ChatFormatting.LIGHT_PURPLE
            text.contains("RNGESUS", ignoreCase = true) -> ChatFormatting.DARK_PURPLE
            else -> ChatFormatting.YELLOW
        }
    }

    private fun normalize(name: String): String = name
        .lowercase()
        .replace(" ", "_")
        .replace("-", "_")
        .replace("iv", "4")
        .replace("v", "5")

    private fun requestPrice(name: String) {
        val key = normalize(name)
        if (!pending.add(key)) return

        NopoMod.coroutineScope.launch(Dispatchers.IO) {
            val price = try {
                lookupPrice(name)
            } catch (_: Exception) {
                null
            }
            if (price != null) {
                prices[key] = price
                val current = activeDrop
                if (current != null && normalize(current.name) == key) {
                    activeDrop = current.copy(value = price)
                }
            }
            pending.remove(key)
        }
    }

    private suspend fun lookupPrice(name: String): Long? = withContext(Dispatchers.IO) {
        val bazaarId = when (normalize(name)) {
            "cropie" -> "CROPIE"
            "squash" -> "SQUASH"
            "fermento" -> "FERMENTO"
            "burrowing_spores" -> "BURROWING_SPORES"
            "rooted_spores" -> "ROOTED_SPORES"
            "cornucopia" -> "CORNUCOPIA"
            "carrot_zest" -> "CARROT_ZEST"
            "deepfries" -> "DEEPFRIES"
            "aggourdian" -> "AGGOURDIAN"
            "cane_knot" -> "CANE_KNOT"
            "melon_juice" -> "MELON_JUICE"
            "cactus_flower" -> "CACTUS_FLOWER"
            "designer_coffee_beans" -> "DESIGNER_COFFEE_BEANS"
            "feastfungus" -> "FEASTFUNGUS"
            "botroot" -> "BOTROOT"
            "salted_sunflower_seeds" -> "SALTED_SUNFLOWER_SEEDS"
            "crystalized_moonlight" -> "CRYSTALIZED_MOONLIGHT"
            "floral_gelatin" -> "FLORAL_GELATIN"
            else -> null
        }

        if (bazaarId != null) {
            try {
                val json = URL.of(URI.create("https://api.hypixel.net/v2/skyblock/bazaar"), null).readText()
                val products = JsonParser.parseString(json).asJsonObject.getAsJsonObject("products")
                val product = products?.getAsJsonObject(bazaarId)
                val sell = product?.getAsJsonObject("quick_status")?.get("sellPrice")?.asDouble
                if (sell != null && sell > 0) return@withContext sell.toLong()
            } catch (_: Exception) {
            }
        }

        try {
            val json = URL.of(URI.create("https://moulberry.codes/lowestbin.json"), null).readText()
            val obj = JsonParser.parseString(json).asJsonObject
            val target = name.lowercase()
            for ((key, value) in obj.entrySet()) {
                if (key.lowercase() == target || key.lowercase().replace('_', ' ') == target) {
                    return@withContext value.asLong
                }
            }
        } catch (_: Exception) {
        }

        null
    }

    override fun onTick(totalTicks: Int) {
        if (activeDrop != null && System.currentTimeMillis() > shownUntil) {
            activeDrop = null
        }
    }

    override fun render(context: GuiGraphicsExtractor) {
        if (!config.enabled) return
        getConfig().pos.render(context) {
            doRender(context)
        }
    }

    override fun doRender(context: GuiGraphicsExtractor) {
        val drop = activeDrop ?: return
        val now = System.currentTimeMillis()
        val age = now - animationStart
        val remaining = shownUntil - now
        val progress = when {
            age < 250L -> (age / 250f).coerceIn(0f, 1f)
            remaining < 400L -> (remaining / 400f).coerceIn(0f, 1f)
            else -> 1f
        }
        val yOffset = ((1f - progress) * -8f).toInt()
        val valueText = drop.value?.let { formatCoins(it * drop.amount) } ?: "..."

        context.pose().pushMatrix()
        context.pose().translate(0f, yOffset.toFloat())
        context.text(
            Minecraft.getInstance().font,
            componentBuilder {
                appendWithColor("${drop.amount}x ${drop.name}", drop.color)
                append("  ")
                appendWithColor(valueText, ChatFormatting.GOLD)
            },
            0,
            0,
            -1
        )
        context.pose().popMatrix()
    }

    private fun formatCoins(value: Long): String = when {
        value >= 1_000_000_000L -> "%.2fB coins".format(value / 1_000_000_000.0)
        value >= 1_000_000L -> "%.2fM coins".format(value / 1_000_000.0)
        value >= 1_000L -> "%.1fk coins".format(value / 1_000.0)
        else -> "$value coins"
    }
}

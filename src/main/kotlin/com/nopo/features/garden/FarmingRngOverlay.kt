package com.nopo.features.garden

import com.google.gson.JsonParser
import com.nopo.NopoMod
import com.nopo.config.PositionConfig
import com.nopo.events.ChatEvent
import com.nopo.events.GuiRendering
import com.nopo.events.TickEvent
import com.nopo.module.ConfigData
import com.nopo.module.FeatureModule
import com.nopo.screens.GuiEditor
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
import java.util.concurrent.CopyOnWriteArrayList
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

    private const val DROP_DURATION_MS = 3_000L
    private const val ROW_GAP = 3

    private data class Drop(
        var amount: Int,
        val name: String,
        val color: ChatFormatting,
        var value: Long?,
        var shownUntil: Long,
        var animationStart: Long,
    )

    // New drops are inserted at the top. The list therefore reads newest -> oldest.
    // The same item is merged into its existing row instead of creating another row.
    private val activeDrops = CopyOnWriteArrayList<Drop>()
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
        val now = System.currentTimeMillis()
        val key = normalize(parsed.second)
        val cachedValue = prices[key]

        synchronized(activeDrops) {
            val existing = activeDrops.firstOrNull { normalize(it.name) == key }
            if (existing != null) {
                // Multiple copies of the same RNG drop are combined into one row.
                existing.amount += parsed.first
                existing.value = existing.value ?: cachedValue
                // Refresh the 3 second lifetime from the latest copy.
                existing.shownUntil = now + DROP_DURATION_MS
                existing.animationStart = now
            } else {
                activeDrops.add(
                    0,
                    Drop(
                        amount = parsed.first,
                        name = parsed.second,
                        color = rarityColor(message),
                        value = cachedValue,
                        shownUntil = now + DROP_DURATION_MS,
                        animationStart = now,
                    )
                )
            }
        }

        if (cachedValue == null) requestPrice(parsed.second)
    }

    private fun parseDrop(raw: String): Pair<Int, String>? {
        val match = rngMessageRegex.find(raw)
        var item: String

        if (match != null) {
            item = match.groups["item"]?.value?.trim() ?: return null
        } else {
            val rarity = raw.contains("RARE DROP", ignoreCase = true) || raw.contains("RNGESUS", ignoreCase = true)
            if (!rarity) return null
            val older = olderDropRegex.find(raw) ?: return null
            item = older.groups["item"]?.value?.trim() ?: return null
        }

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
                synchronized(activeDrops) {
                    activeDrops.firstOrNull { normalize(it.name) == key }?.value = price
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
                if (key.lowercase() == target || key.lowercase().replace('_', ' ') == target) return@withContext value.asLong
            }
        } catch (_: Exception) {
        }
        null
    }

    override fun onTick(totalTicks: Int) {
        val now = System.currentTimeMillis()
        synchronized(activeDrops) {
            activeDrops.removeIf { now >= it.shownUntil }
        }
    }

    override fun render(context: GuiGraphicsExtractor) {
        if (!config.enabled) return
        getConfig().pos.render(context) { doRender(context) }
    }

    override fun doRender(context: GuiGraphicsExtractor) {
        val editing = Minecraft.getInstance().gui.screen() is GuiEditor
        val drops = if (editing) {
            listOf(Drop(1, "Crystalized Moonlight", ChatFormatting.BLUE, 484_000L, Long.MAX_VALUE, 0L))
        } else {
            synchronized(activeDrops) { activeDrops.toList() }
        }
        if (drops.isEmpty()) return

        val now = System.currentTimeMillis()
        var y = 0
        for (drop in drops) {
            val age = now - drop.animationStart
            val remaining = drop.shownUntil - now
            val progress = if (editing) 1f else when {
                age < 200L -> (age / 200f).coerceIn(0f, 1f)
                remaining < 250L -> (remaining / 250f).coerceIn(0f, 1f)
                else -> 1f
            }
            val yOffset = if (editing) 0 else ((1f - progress) * -8f).toInt()
            val valueText = drop.value?.let { formatCoins(it * drop.amount) } ?: "..."

            context.pose().pushMatrix()
            context.pose().translate(0f, (y + yOffset).toFloat())
            context.text(
                Minecraft.getInstance().font,
                componentBuilder {
                    appendWithColor("x${drop.amount} ${drop.name}", drop.color)
                    append("  ")
                    appendWithColor(valueText, ChatFormatting.GOLD)
                },
                0,
                0,
                -1
            )
            context.pose().popMatrix()

            y += Minecraft.getInstance().font.lineHeight + ROW_GAP
        }
    }

    private fun formatCoins(value: Long): String = when {
        value >= 1_000_000_000L -> "%.2fB coins".format(value / 1_000_000_000.0)
        value >= 1_000_000L -> "%.2fM coins".format(value / 1_000_000.0)
        value >= 1_000L -> "%.1fk coins".format(value / 1_000.0)
        else -> "$value coins"
    }
}

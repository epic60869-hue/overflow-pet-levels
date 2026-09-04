package com.nopo

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.mojang.blaze3d.platform.InputConstants
import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.item.v1.ItemTooltipCallback
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper
import net.minecraft.ChatFormatting
import net.minecraft.client.KeyMapping
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.CharacterEvent
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.client.input.KeyEvent
import net.minecraft.network.chat.Component
import net.minecraft.core.component.DataComponents
import net.minecraft.resources.Identifier
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.item.TooltipFlag
import org.lwjgl.glfw.GLFW
import java.nio.file.Files
import java.nio.file.Path

object OverflowPetLevels : ClientModInitializer {
    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()
    private lateinit var configPath: Path
    private var enabled = true
    private var hudX = 660
    private var hudY = 420
    private var hudScale = 1.0f
    private var display: List<Component>? = null

    private val petNameRegex = Regex(" +\\[Lvl (?<level>\\d+)] (?<name>.*)")
    private val overflowXpRegex = Regex(" +\\+(?<xp>[\\d,.]+) XP")
    private val keyCategory = KeyMapping.Category.register(
        Identifier.fromNamespaceAndPath("overflow-pet-levels", "main")
    )
    private val openEditorKey = KeyMappingHelper.registerKeyMapping(
        KeyMapping(
            "key.overflow-pet-levels.editor",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_P,
            keyCategory
        )
    )

    private var currentPet = ""
    private var currentOverflowLevel = -1

    override fun onInitializeClient() {
        configPath = Minecraft.getInstance().gameDirectory.toPath().resolve("config/overflow-pet-levels.json")
        loadConfig()

        ClientTickEvents.END_CLIENT_TICK.register { client ->
            while (openEditorKey.consumeClick()) {
                if (client.player != null && client.screen == null) {
                    client.setScreen(PetHudEditor())
                }
            }
            updatePetFromTab(client)
        }

        HudElementRegistry.attachElementBefore(
            VanillaHudElements.CHAT,
            Identifier.fromNamespaceAndPath("overflow-pet-levels", "pet_hud")
        ) { context, _ ->
            renderHud(context)
        }

        ItemTooltipCallback.EVENT.register(ItemTooltipCallback { itemStack: ItemStack, _: Item.TooltipContext, _: TooltipFlag?, list: MutableList<Component> ->
            addOverflowTooltip(itemStack, list)
        })
    }

    private fun updatePetFromTab(client: Minecraft) {
        val connection = client.connection ?: run {
            display = null
            return
        }

        val tab = connection.onlinePlayers.mapNotNull { it.tabListDisplayName }
        val petLines = mutableListOf<Component>()
        var readingPet = false

        for (line in tab) {
            val text = line.string
            if (text.trim() == "Pet:") {
                readingPet = true
                petLines.add(line)
                continue
            }
            if (readingPet) {
                if (text.isEmpty() || !text.startsWith(" ")) break
                petLines.add(line)
            }
        }

        if (petLines.size < 2) {
            display = null
            currentPet = ""
            currentOverflowLevel = -1
            return
        }

        var realLevel = -1
        var overflowLevel = -1
        var name = ""
        var rarity = PetRarity.LEGENDARY
        var foundOverflowXp = false
        val result = mutableListOf<Component>()

        for (line in petLines) {
            val string = line.string
            val nameMatch = petNameRegex.matchEntire(string)
            if (nameMatch != null) {
                realLevel = nameMatch.groups["level"]!!.value.toInt()
                overflowLevel = realLevel
                name = nameMatch.groups["name"]!!.value
                rarity = rarityFromComponent(line)
                result.add(line)
                continue
            }

            val xpMatch = overflowXpRegex.matchEntire(string)
            if (xpMatch != null && realLevel >= 0) {
                foundOverflowXp = true
                val xp = xpMatch.groups["xp"]!!.value.replace(",", "").toFloat() + getCalculativeXpForLevel(realLevel, rarity)
                overflowLevel = calcLevel(xp, rarity)
                if (realLevel == 200) overflowLevel--

                val progress = calcLeftOverXp(xp, rarity)
                val nextLevel = if (rarity != PetRarity.LEGENDARY && overflowLevel < 100) overflowLevel else overflowLevel
                val nextXp = getXpForLevel(nextLevel.coerceAtLeast(0), rarity)
                val percent = if (nextXp > 0) progress / nextXp * 100f else 100f

                result.add(
                    Component.literal(" ${formatNumber(progress)}/$nextXp XP (${formatOneDecimal(percent)}%)")
                        .withStyle(ChatFormatting.YELLOW)
                )
                continue
            }

            result.add(line)
        }

        if (realLevel >= 0 && name.isNotEmpty()) {
            val petNameComponent = petLines.firstOrNull { it.string.contains(name) } ?: Component.literal(name)
            if (result.size >= 2) {
                result[1] = generateCustomName(overflowLevel, realLevel, petNameComponent, rarity)
            }
        }

        display = result

        if (foundOverflowXp && name == currentPet && currentOverflowLevel + 1 == overflowLevel && overflowLevel > 0) {
            client.player?.sendSystemMessage(
                Component.literal("Your ").withStyle(ChatFormatting.GREEN)
                    .append(Component.literal(name))
                    .append(Component.literal(" leveled up to level ").withStyle(ChatFormatting.GREEN))
                    .append(Component.literal(overflowLevel.toString()).withStyle(ChatFormatting.BLUE))
                    .append(Component.literal("!"))
            )
        }

        currentPet = name
        currentOverflowLevel = overflowLevel
    }

    private fun rarityFromComponent(component: Component): PetRarity {
        val color = component.style.color?.value ?: return PetRarity.LEGENDARY
        return when (color) {
            0xFFFFFF -> PetRarity.COMMON
            0x55FF55 -> PetRarity.UNCOMMON
            0x5555FF -> PetRarity.RARE
            0xAA00AA -> PetRarity.EPIC
            else -> PetRarity.LEGENDARY
        }
    }

    private fun renderHud(context: GuiGraphicsExtractor) {
        val client = Minecraft.getInstance()
        if (!enabled || client.options.hideGui || !isSkyblock()) return
        val lines = display ?: return

        val matrices = context.pose()
        matrices.pushMatrix()
        matrices.translate(hudX.toFloat(), hudY.toFloat())
        matrices.scale(hudScale, hudScale)

        val font = client.font
        for ((index, line) in lines.withIndex()) {
            context.text(font, line, 0f, index * 10f, 0xFFFFFFFF.toInt())
        }
        matrices.popMatrix()
    }

    private fun addOverflowTooltip(itemStack: ItemStack, lore: MutableList<Component>) {
        if (itemStack.item != Items.PLAYER_HEAD) return
        val nbt = itemStack.get(DataComponents.CUSTOM_DATA)?.copyTag() ?: return
        if (!nbt.contains("petInfo")) return
        val petInfo = nbt.get("petInfo")?.asString()?.orElse(null) ?: return
        val json: JsonObject = try { Gson().fromJson(petInfo, JsonObject::class.java) } catch (_: Exception) { return }
        if (!json.has("exp")) return
        val xp = json.get("exp")?.asFloat ?: return

        for ((index, text) in lore.withIndex()) {
            if (text.string.contains("MAX LEVEL")) {
                lore[index] = Component.literal("MAX LEVEL").withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD)
                    .append(Component.literal(" [").withStyle(ChatFormatting.GRAY))
                    .append(Component.literal("${calcLevel(xp, PetRarity.LEGENDARY)}✦").withStyle(ChatFormatting.GOLD))
                    .append(Component.literal("]").withStyle(ChatFormatting.GRAY))
                return
            }
        }
    }

    private fun generateCustomName(overflowLevel: Int, realLevel: Int, nameComponent: Component, rarity: PetRarity): Component {
        val builder = Component.literal(" [Lvl $overflowLevel").withStyle(ChatFormatting.GRAY)
        if (rarity != PetRarity.LEGENDARY && realLevel != overflowLevel && overflowLevel < 100) {
            builder.append(Component.literal(" ($realLevel)").withStyle(ChatFormatting.GRAY))
        }
        builder.append(Component.literal("] ").withStyle(ChatFormatting.GRAY))
        builder.append(nameComponent)
        return builder
    }

    private fun getCalculativeXpForLevel(level: Int, rarity: PetRarity): Int {
        var xp = 0
        for (i in 0 until level) xp += getXpForLevel(i, rarity)
        return xp
    }

    private fun getXpForLevel(level: Int, rarity: PetRarity): Int {
        val offset = rarity.offset + level
        return if (offset in xpTable.indices) xpTable[offset] else 1_886_700
    }

    private fun calcLevel(xp: Float, rarity: PetRarity): Int {
        var remaining = xp
        var level = 0
        while (remaining > 0) {
            remaining -= getXpForLevel(level, rarity)
            level++
            if (level > 300) break
        }
        return level.coerceAtLeast(1)
    }

    private fun calcLeftOverXp(xp: Float, rarity: PetRarity): Float {
        var remaining = xp
        var level = 0
        while (remaining > 0) {
            val required = getXpForLevel(level, rarity)
            if (remaining > required) remaining -= required
            else return remaining
            level++
            if (level > 300) break
        }
        return 0f
    }

    private fun isSkyblock(): Boolean {
        return Minecraft.getInstance().player != null && display != null
    }

    private fun formatNumber(value: Float): String = String.format("%,.1f", value)
    private fun formatOneDecimal(value: Float): String = String.format("%.1f", value)

    private fun loadConfig() {
        try {
            if (!Files.exists(configPath)) return
            val obj = gson.fromJson(Files.readString(configPath), JsonObject::class.java)
            enabled = obj.get("enabled")?.asBoolean ?: true
            hudX = obj.get("x")?.asInt ?: 660
            hudY = obj.get("y")?.asInt ?: 420
            hudScale = obj.get("scale")?.asFloat ?: 1f
        } catch (_: Exception) { }
    }

    private fun saveConfig() {
        try {
            Files.createDirectories(configPath.parent)
            val obj = JsonObject()
            obj.addProperty("enabled", enabled)
            obj.addProperty("x", hudX)
            obj.addProperty("y", hudY)
            obj.addProperty("scale", hudScale)
            Files.writeString(configPath, gson.toJson(obj))
        } catch (_: Exception) { }
    }

    private enum class PetRarity(val offset: Int) {
        COMMON(0), UNCOMMON(6), RARE(11), EPIC(15), LEGENDARY(20)
    }

    private val xpTable = listOf(
        100,110,120,130,145,160,175,190,210,230,250,275,300,330,360,400,440,490,540,600,
        660,730,800,880,960,1050,1150,1260,1380,1510,1650,1800,1960,2130,2310,2500,2700,2920,3160,3420,
        3700,4000,4350,4750,5200,5700,6300,7000,7800,8700,9700,10800,12000,13300,14700,16200,17800,19500,21300,23200,
        25200,27400,29800,32400,35200,38200,41400,44800,48400,52200,56200,60400,64800,69400,74200,79200,84700,90700,97200,104200,
        111700,119700,128200,137200,146700,156700,167700,179700,192700,206700,221700,237700,254700,272700,291700,311700,333700,357700,383700,411700,
        441700,476700,516700,561700,611700,666700,726700,791700,861700,936700,1016700,1101700,1191700,1286700,1386700,1496700,1616700,1746700,1886700
    )

    private class PetHudEditor : Screen(Component.literal("Overflow Pet Levels HUD Editor")) {
        private var savedX = hudX
        private var savedY = hudY
        private var savedScale = hudScale

        override fun extractRenderState(context: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
            super.extractRenderState(context, mouseX, mouseY, partialTick)
            val font = Minecraft.getInstance().font
            context.text(font, Component.literal("Overflow Pet Levels HUD Editor"), 8f, 8f, 0xFFFFFFFF.toInt())
            context.text(font, Component.literal("Move mouse = move HUD | Scroll = scale | R = reset | Left click = save"), 8f, 20f, 0xFFFFFFFF.toInt())
            context.pose().pushMatrix()
            context.pose().translate(hudX.toFloat(), hudY.toFloat())
            context.pose().scale(hudScale, hudScale)
            val sample = display ?: listOf(
                Component.literal("Pet:").withStyle(ChatFormatting.YELLOW, ChatFormatting.BOLD),
                Component.literal(" [Lvl 231] ").withStyle(ChatFormatting.GRAY).append(Component.literal("Golden Dragon").withStyle(ChatFormatting.GOLD)),
                Component.literal(" 1,832,110.4/1.9M XP (97.1%)").withStyle(ChatFormatting.YELLOW)
            )
            for ((index, line) in sample.withIndex()) context.text(font, line, 0f, index * 10f, 0xFFFFFFFF.toInt())
            context.pose().popMatrix()
        }

        override fun mouseMoved(x: Double, y: Double) {
            hudX = x.toInt()
            hudY = y.toInt()
        }

        override fun mouseClicked(mouseEvent: MouseButtonEvent, bl: Boolean): Boolean {
            if (mouseEvent.button() == 0) {
                savedX = hudX
                savedY = hudY
                savedScale = hudScale
                saveConfig()
                onClose()
                return true
            }
            return super.mouseClicked(mouseEvent, bl)
        }

        override fun charTyped(characterEvent: CharacterEvent): Boolean {
            if (characterEvent.codepointAsString().equals("r", true)) hudScale = 1f
            return super.charTyped(characterEvent)
        }

        override fun keyPressed(keyEvent: KeyEvent): Boolean {
            if (keyEvent.key() == GLFW.GLFW_KEY_ESCAPE) {
                hudX = savedX
                hudY = savedY
                hudScale = savedScale
                onClose()
                return true
            }
            return super.keyPressed(keyEvent)
        }

        override fun mouseScrolled(x: Double, y: Double, scrollX: Double, scrollY: Double): Boolean {
            hudScale = (hudScale + if (scrollY > 0) 0.1f else -0.1f).coerceIn(0.2f, 5f)
            return true
        }
    }
}

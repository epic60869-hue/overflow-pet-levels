package com.nopo

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.mojang.blaze3d.platform.InputConstants
import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.item.v1.ItemTooltipCallback
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements
import net.minecraft.ChatFormatting
import net.minecraft.client.KeyMapping
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.item.TooltipFlag
import net.minecraft.core.component.DataComponents
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
    private var currentPet = ""
    private var currentOverflowLevel = -1

    private val petRegex = Regex("\\s*\\[Lvl (?<level>\\d+)] (?<name>.*)")
    private val xpRegex = Regex("\\s*\\+(?<xp>[\\d,.]+) XP")

    private val keyCategory = KeyMapping.Category.register(
        Identifier.fromNamespaceAndPath("overflow-pet-levels", "main")
    )
    private val editorKey = KeyMappingHelper.registerKeyMapping(
        KeyMapping("key.overflow-pet-levels.editor", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_P, keyCategory)
    )

    override fun onInitializeClient() {
        configPath = Minecraft.getInstance().gameDirectory.toPath().resolve("config/overflow-pet-levels.json")
        loadConfig()

        ClientTickEvents.END_CLIENT_TICK.register { client ->
            while (editorKey.consumeClick()) {
                if (client.player != null && client.gui.screen() == null) {
                    client.gui.setScreen(PetHudEditor())
                }
            }
            updatePetFromTab(client)
        }

        HudElementRegistry.attachElementBefore(
            VanillaHudElements.CHAT,
            Identifier.fromNamespaceAndPath("overflow-pet-levels", "pet_hud")
        ) { context, _ -> renderHud(context) }

        ItemTooltipCallback.EVENT.register(ItemTooltipCallback { stack: ItemStack, _: Item.TooltipContext, _: TooltipFlag?, lines: MutableList<Component> ->
            addOverflowTooltip(stack, lines)
        })
    }

    private fun updatePetFromTab(client: Minecraft) {
        val connection = client.connection ?: run {
            display = null
            return
        }

        val tab = connection.onlinePlayers.mapNotNull { it.tabListDisplayName }
        val petLines = mutableListOf<Component>()
        var reading = false

        for (line in tab) {
            val text = line.string
            if (text.trim() == "Pet:") {
                reading = true
                petLines += line
                continue
            }
            if (reading) {
                if (text.isEmpty() || !text.startsWith(" ")) break
                petLines += line
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
        var foundXp = false
        val result = mutableListOf<Component>()

        for (line in petLines) {
            val text = line.string
            val pet = petRegex.matchEntire(text)
            if (pet != null) {
                realLevel = pet.groups["level"]!!.value.toInt()
                overflowLevel = realLevel
                name = pet.groups["name"]!!.value
                rarity = rarityFromComponent(line)
                result += line
                continue
            }

            val xp = xpRegex.matchEntire(text)
            if (xp != null && realLevel >= 0) {
                foundXp = true
                val totalXp = xp.groups["xp"]!!.value.replace(",", "").toFloat() + xpToReachLevel(realLevel, rarity)
                overflowLevel = calcLevel(totalXp, rarity)
                if (realLevel == 200) overflowLevel--

                val progress = leftoverXp(totalXp, rarity)
                val needed = xpForLevel(overflowLevel.coerceAtLeast(0), rarity)
                val percent = if (needed > 0) progress / needed * 100f else 100f
                result += Component.literal(" ${formatNumber(progress)}/$needed XP (${String.format("%.1f", percent)}%)")
                    .withStyle(ChatFormatting.YELLOW)
                continue
            }
            result += line
        }

        if (realLevel >= 0 && name.isNotEmpty() && result.size >= 2) {
            val nameComponent = petLines.firstOrNull { it.string.contains(name) } ?: Component.literal(name)
            result[1] = customPetName(overflowLevel, realLevel, nameComponent, rarity)
        }

        display = result

        if (foundXp && name == currentPet && overflowLevel == currentOverflowLevel + 1 && overflowLevel > 0) {
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
        return when (component.style.color?.value) {
            0xFFFFFF -> PetRarity.COMMON
            0x55FF55 -> PetRarity.UNCOMMON
            0x5555FF -> PetRarity.RARE
            0xAA00AA -> PetRarity.EPIC
            else -> PetRarity.LEGENDARY
        }
    }

    private fun renderHud(context: GuiGraphicsExtractor) {
        val client = Minecraft.getInstance()
        if (!enabled || client.gui.hud.isHidden() || !isSkyblock()) return
        val lines = display ?: return
        val matrix = context.pose()
        matrix.pushMatrix()
        matrix.translate(hudX.toFloat(), hudY.toFloat())
        matrix.scale(hudScale, hudScale)

        for ((index, line) in lines.withIndex()) {
            context.text(client.font, line, 0, index * 10, 0xFFFFFFFF.toInt())
        }
        matrix.popMatrix()
    }

    private fun addOverflowTooltip(stack: ItemStack, lines: MutableList<Component>) {
        if (stack.item != Items.PLAYER_HEAD) return
        val tag = stack.get(DataComponents.CUSTOM_DATA)?.copyTag() ?: return
        val petInfo = tag.get("petInfo")?.asString()?.orElse(null) ?: return
        val json = try { Gson().fromJson(petInfo, JsonObject::class.java) } catch (_: Exception) { return }
        val xp = json.get("exp")?.asFloat ?: return

        for (i in lines.indices) {
            if (lines[i].string.contains("MAX LEVEL")) {
                lines[i] = Component.literal("MAX LEVEL").withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD)
                    .append(Component.literal(" [").withStyle(ChatFormatting.GRAY))
                    .append(Component.literal("${calcLevel(xp, PetRarity.LEGENDARY)}✦").withStyle(ChatFormatting.GOLD))
                    .append(Component.literal("]").withStyle(ChatFormatting.GRAY))
                return
            }
        }
    }

    private fun customPetName(level: Int, realLevel: Int, name: Component, rarity: PetRarity): Component {
        val result = Component.literal(" [Lvl $level").withStyle(ChatFormatting.GRAY)
        if (rarity != PetRarity.LEGENDARY && level != realLevel && level < 100) {
            result.append(Component.literal(" ($realLevel)").withStyle(ChatFormatting.GRAY))
        }
        result.append(Component.literal("] ").withStyle(ChatFormatting.GRAY))
        return result.append(name)
    }

    private fun xpToReachLevel(level: Int, rarity: PetRarity): Int {
        var total = 0
        for (i in 0 until level) total += xpForLevel(i, rarity)
        return total
    }

    private fun xpForLevel(level: Int, rarity: PetRarity): Int {
        val index = rarity.offset + level
        return if (index in xpTable.indices) xpTable[index] else 1_886_700
    }

    private fun calcLevel(xp: Float, rarity: PetRarity): Int {
        var remaining = xp
        var level = 0
        while (remaining > 0 && level <= 300) {
            remaining -= xpForLevel(level, rarity)
            level++
        }
        return level.coerceAtLeast(1)
    }

    private fun leftoverXp(xp: Float, rarity: PetRarity): Float {
        var remaining = xp
        var level = 0
        while (remaining > 0 && level <= 300) {
            val required = xpForLevel(level, rarity)
            if (remaining > required) remaining -= required else return remaining
            level++
        }
        return 0f
    }

    private fun isSkyblock(): Boolean = Minecraft.getInstance().player != null && display != null
    private fun formatNumber(value: Float): String = String.format("%,.1f", value)

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

    private enum class PetRarity(val offset: Int) { COMMON(0), UNCOMMON(6), RARE(11), EPIC(15), LEGENDARY(20) }

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
            context.text(font, "Overflow Pet Levels HUD Editor", 8, 8, 0xFFFFFFFF.toInt(), true)
            context.text(font, "Move mouse = move HUD | Wheel = scale | R = reset | Left click = save", 8, 20, 0xFFFFFFFF.toInt(), true)
            context.pose().pushMatrix()
            context.pose().translate(hudX.toFloat(), hudY.toFloat())
            context.pose().scale(hudScale, hudScale)
            val sample = display ?: listOf(
                Component.literal("Pet:").withStyle(ChatFormatting.YELLOW, ChatFormatting.BOLD),
                Component.literal(" [Lvl 231] ").withStyle(ChatFormatting.GRAY).append(Component.literal("Golden Dragon").withStyle(ChatFormatting.GOLD)),
                Component.literal(" 1,832,110.4/1,886,700 XP (97.1%)").withStyle(ChatFormatting.YELLOW)
            )
            for ((index, line) in sample.withIndex()) context.text(font, line, 0, index * 10, 0xFFFFFFFF.toInt(), true)
            context.pose().popMatrix()
        }

        override fun mouseMoved(x: Double, y: Double) {
            hudX = x.toInt()
            hudY = y.toInt()
        }

        override fun mouseClicked(mouseX: Double, mouseY: Double, button: Int): Boolean {
            if (button == 0) {
                saveConfig()
                onClose()
                return true
            }
            return super.mouseClicked(mouseX, mouseY, button)
        }

        override fun keyPressed(keyCode: Int, scanCode: Int, modifiers: Int): Boolean {
            if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
                hudX = savedX
                hudY = savedY
                hudScale = savedScale
                onClose()
                return true
            }
            if (keyCode == GLFW.GLFW_KEY_R) {
                hudScale = 1f
                return true
            }
            return super.keyPressed(keyCode, scanCode, modifiers)
        }

        override fun mouseScrolled(mouseX: Double, mouseY: Double, horizontalAmount: Double, verticalAmount: Double): Boolean {
            hudScale = (hudScale + if (verticalAmount > 0) 0.1f else -0.1f).coerceIn(0.2f, 5f)
            return true
        }
    }
}

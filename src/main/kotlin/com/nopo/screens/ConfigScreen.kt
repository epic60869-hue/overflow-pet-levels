package com.nopo.screens

import com.nopo.NopoMod
import com.nopo.config.ConfigManager
import com.nopo.config.PositionConfig
import com.nopo.events.GuiRendering
import com.nopo.module.FeatureModule
import com.nopo.utils.Utils
import net.minecraft.ChatFormatting
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.network.chat.Component
import kotlin.math.max
import kotlin.math.min

/**
 * Compact Nopo configuration screen with a small pixel-game style inspired by
 * the supplied reference image. This is Nopo's own implementation.
 */
class ConfigScreen(var currentCategory: String? = null) : Screen(Component.literal("Nopo Config")) {

    private var scroll = 0
    private var hoverFeature: FeatureModule? = null
    private var hoverMove = false

    private val categories: List<String>
        get() = Utils.getAllCategories()

    private fun featuresFor(category: String?): List<FeatureModule> {
        if (category == null) return emptyList()
        var active = false
        return NopoMod.modules.mapNotNull { module ->
            if (module is com.nopo.categories.Category) active = module.moduleName.equals(category, ignoreCase = true)
            if (!active || module !is FeatureModule || module.shouldBeHidden()) return@mapNotNull null
            module
        }
    }

    override fun init() {
        super.init()
        if (currentCategory == null || categories.none { it.equals(currentCategory, true) }) {
            currentCategory = categories.firstOrNull()
        }
        NopoMod.config.usedConfigMenu = true
        scroll = 0
    }

    // Deliberately small in logical GUI coordinates so it stays compact on high-resolution screens.
    private fun panelWidth(): Int = min(560, width - 40)
    private fun panelHeight(): Int = min(400, height - 40)
    private fun left(): Int = (width - panelWidth()) / 2
    private fun top(): Int = (height - panelHeight()) / 2
    private fun sidebarWidth(): Int = 125

    private fun drawPanel(context: GuiGraphicsExtractor, x: Int, y: Int, w: Int, h: Int) {
        context.fill(x - 3, y - 3, x + w + 3, y + h + 3, 0xFF08090C.toInt())
        context.fill(x, y, x + w, y + h, 0xFF17171D.toInt())
        context.outline(x, y, x + w, y + h, 0xFF050609.toInt())
        context.outline(x + 2, y + 2, x + w - 2, y + h - 2, 0xFF35343D.toInt())
    }

    override fun extractRenderState(context: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, f: Float) {
        super.extractRenderState(context, mouseX, mouseY, f)

        val x = left()
        val y = top()
        val w = panelWidth()
        val h = panelHeight()
        val headerH = 42
        val sidebarW = sidebarWidth()

        context.fill(0, 0, width, height, 0xA0000000.toInt())
        drawPanel(context, x, y, w, h)

        context.fill(x + 3, y + 3, x + w - 3, y + headerH, 0xFF1E1E25.toInt())
        context.outline(x + 3, y + 3, x + w - 3, y + headerH, 0xFF08090C.toInt())

        context.text(font, Component.literal("Nopo Mod").withStyle(ChatFormatting.BOLD), x + 12, y + 10, 0xFFF0F0F2.toInt())
        context.text(font, Component.literal("Configuration"), x + 12, y + 26, 0xFF89868F.toInt())
        context.text(font, Component.literal("ESC"), x + w - 25, y + 12, 0xFF686670.toInt())

        val sideX = x + 3
        val sideY = y + headerH
        context.fill(sideX, sideY, sideX + sidebarW, y + h - 3, 0xFF121319.toInt())
        context.fill(sideX + sidebarW - 1, sideY, sideX + sidebarW, y + h - 3, 0xFF34333B.toInt())

        context.text(font, Component.literal("Categories").withStyle(ChatFormatting.BOLD), sideX + 12, sideY + 12, 0xFFC8A6FF.toInt())

        var cy = sideY + 35
        for (category in categories) {
            val selected = category.equals(currentCategory, true)
            val itemX = sideX + 8
            val itemW = sidebarW - 16
            if (selected) {
                context.fill(itemX, cy - 2, itemX + itemW, cy + 19, 0xFF211B2C.toInt())
                context.fill(itemX, cy - 2, itemX + 2, cy + 19, 0xFFC47CFF.toInt())
            }
            val categoryText = Component.literal(category)
                .withStyle(if (selected) ChatFormatting.UNDERLINE else ChatFormatting.RESET)
            context.text(font, categoryText, itemX + 9, cy + 3, if (selected) 0xFFE1B8FF.toInt() else 0xFFC0BEC7.toInt())
            cy += 25
            if (cy > y + h - 20) break
        }

        val mainX = sideX + sidebarW + 10
        val mainY = sideY + 9
        val mainW = w - sidebarW - 22
        val contentBottom = y + h - 12
        val features = featuresFor(currentCategory)

        context.text(font, Component.literal(currentCategory ?: "Configuration").withStyle(ChatFormatting.BOLD), mainX + 5, mainY + 2, 0xFFE0D7E8.toInt())
        context.fill(mainX + 5, mainY + 17, mainX + mainW - 5, mainY + 18, 0xFF383640.toInt())

        val listY = mainY + 25
        val cardHeight = 76
        val gap = 7
        val visibleHeight = contentBottom - listY
        val maxScroll = max(0, features.size * (cardHeight + gap) - visibleHeight)
        scroll = scroll.coerceIn(0, maxScroll)

        hoverFeature = null
        hoverMove = false
        var fy = listY - scroll

        for (feature in features) {
            if (fy + cardHeight >= listY && fy <= contentBottom) {
                val inside = mouseX >= mainX && mouseX <= mainX + mainW && mouseY >= fy && mouseY <= fy + cardHeight
                if (inside) hoverFeature = feature

                val enabled = feature.config.enabled
                val cardX = mainX + 5
                val cardW = mainW - 10

                context.fill(cardX, fy, cardX + cardW, fy + cardHeight, 0xFF1B1A21.toInt())
                context.outline(cardX, fy, cardX + cardW, fy + cardHeight, if (inside) 0xFF5B5363.toInt() else 0xFF35333C.toInt())
                context.fill(cardX + 2, fy + 2, cardX + cardW - 2, fy + 4, if (enabled) 0xFF57396C.toInt() else 0xFF282630.toInt())

                val title = feature.configData?.name ?: Component.literal(feature.moduleName)
                context.text(font, title.copy().withStyle(ChatFormatting.BOLD), cardX + 9, fy + 9, 0xFFE4E1E7.toInt())

                feature.configData?.description?.let {
                    context.text(font, it, cardX + 9, fy + 28, 0xFF97949E.toInt())
                }

                val toggleW = 55
                val toggleH = 19
                val toggleX = cardX + cardW - toggleW - 8
                val toggleY = fy + 7
                context.fill(toggleX - 1, toggleY - 1, toggleX + toggleW + 1, toggleY + toggleH + 1, 0xFF090A0E.toInt())
                context.fill(toggleX, toggleY, toggleX + toggleW, toggleY + toggleH, if (enabled) 0xFF2A855F.toInt() else 0xFF292830.toInt())
                context.text(font, Component.literal(if (enabled) "ON" else "OFF"), toggleX + if (enabled) 17 else 14, toggleY + 5, 0xFFF5F2F6.toInt())

                if (feature.config is PositionConfig && feature is GuiRendering) {
                    val moveX = cardX + cardW - toggleW - 8
                    val moveY = fy + 51
                    val moveHovered = mouseX >= moveX && mouseX <= moveX + toggleW && mouseY >= moveY && mouseY <= moveY + 18
                    if (moveHovered) {
                        hoverFeature = feature
                        hoverMove = true
                    }
                    context.fill(moveX - 1, moveY - 1, moveX + toggleW + 1, moveY + 19, 0xFF090A0E.toInt())
                    context.fill(moveX, moveY, moveX + toggleW, moveY + 18, if (moveHovered) 0xFF3A3042.toInt() else 0xFF25232B.toInt())
                    context.text(font, Component.literal("MOVE"), moveX + 12, moveY + 5, 0xFFD2CBD7.toInt())
                }
            }
            fy += cardHeight + gap
        }

        if (features.isEmpty()) {
            context.text(font, Component.literal("No features in this category"), mainX + 10, listY + 10, 0xFF85818B.toInt())
        }

        if (maxScroll > 0) {
            val trackX = x + w - 8
            context.fill(trackX, listY, trackX + 3, contentBottom, 0xFF292730.toInt())
            val trackH = contentBottom - listY
            val thumbHeight = max(20, trackH * trackH / (trackH + maxScroll))
            val thumbY = listY + ((trackH - thumbHeight) * scroll / maxScroll)
            context.fill(trackX, thumbY, trackX + 3, thumbY + thumbHeight, 0xFF746D7D.toInt())
        }

        if (hoverFeature != null) {
            val hint = if (hoverMove) "Click MOVE to position the overlay" else "Click a feature to toggle it"
            context.text(font, Component.literal(hint), mainX + 7, y + h - 9, 0xFF716D77.toInt())
        }
    }

    override fun mouseClicked(mouseEvent: MouseButtonEvent, bl: Boolean): Boolean {
        val mouseX = mouseEvent.x().toInt()
        val mouseY = mouseEvent.y().toInt()
        val x = left()
        val y = top()
        val w = panelWidth()
        val h = panelHeight()
        val headerH = 42
        val sidebarW = sidebarWidth()

        if (mouseX < x || mouseX > x + w || mouseY < y || mouseY > y + h) return super.mouseClicked(mouseEvent, bl)
        if (mouseY < y + headerH) return true

        val sideX = x + 3
        if (mouseX >= sideX && mouseX < sideX + sidebarW) {
            var cy = y + headerH + 35
            for (category in categories) {
                if (mouseY >= cy - 2 && mouseY <= cy + 19) {
                    currentCategory = category
                    scroll = 0
                    return true
                }
                cy += 25
                if (cy > y + h - 20) break
            }
            return true
        }

        val mainX = sideX + sidebarW + 10
        val mainW = w - sidebarW - 22
        val mainY = y + headerH + 9
        val listY = mainY + 25
        val cardHeight = 76
        val gap = 7
        val features = featuresFor(currentCategory)
        val visibleHeight = y + h - 12 - listY
        val maxScroll = max(0, features.size * (cardHeight + gap) - visibleHeight)
        scroll = scroll.coerceIn(0, maxScroll)

        val index = ((mouseY - listY + scroll) / (cardHeight + gap))
        if (index < 0 || index >= features.size) return true

        val feature = features[index]
        val fy = listY + index * (cardHeight + gap) - scroll
        val cardX = mainX + 5
        val cardW = mainW - 10
        if (mouseY < fy || mouseY > fy + cardHeight || mouseX < cardX || mouseX > cardX + cardW) return true

        val toggleW = 55
        val moveX = cardX + cardW - toggleW - 8
        if (feature.config is PositionConfig && feature is GuiRendering) {
            val moveY = fy + 51
            if (mouseX >= moveX && mouseX <= moveX + toggleW && mouseY >= moveY && mouseY <= moveY + 18) {
                NopoMod.screenToOpen = GuiEditor((feature.config as PositionConfig).pos) { context -> feature.doRender(context) }
                return true
            }
        }

        feature.config.enabled = !feature.config.enabled
        ConfigManager.save()
        return true
    }

    override fun mouseScrolled(d: Double, e: Double, scrollX: Double, scrollY: Double): Boolean {
        scroll = (scroll - (scrollY * 18).toInt()).coerceIn(0, Int.MAX_VALUE)
        return true
    }

    override fun isPauseScreen(): Boolean = false
}

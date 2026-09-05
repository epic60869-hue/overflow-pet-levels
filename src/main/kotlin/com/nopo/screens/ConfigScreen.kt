package com.nopo.screens

import com.nopo.NopoMod
import com.nopo.categories.Category
import com.nopo.config.ConfigManager
import com.nopo.config.PositionConfig
import com.nopo.events.GuiRendering
import com.nopo.module.FeatureModule
import com.nopo.utils.Utils
import net.minecraft.ChatFormatting
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.network.chat.Component
import kotlin.math.max
import kotlin.math.min

/**
 * Nopo configuration screen with a compact, pixel-game style inspired by the
 * second reference image. This is Nopo's own implementation and has no
 * Skysoft/SkyHanni dependency.
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
            if (module is Category) active = module.moduleName.equals(category, ignoreCase = true)
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

    // The layout intentionally stays compact like the reference even on a large display.
    private fun panelWidth(): Int = min(930, width - 80)
    private fun panelHeight(): Int = min(650, height - 70)
    private fun left(): Int = (width - panelWidth()) / 2
    private fun top(): Int = (height - panelHeight()) / 2
    private fun sidebarWidth(): Int = 190

    private fun drawPanel(context: GuiGraphicsExtractor, x: Int, y: Int, w: Int, h: Int) {
        // Outer frame and inset frame.
        context.fill(x - 4, y - 4, x + w + 4, y + h + 4, 0xFF0B0D11.toInt())
        context.fill(x, y, x + w, y + h, 0xFF17171D.toInt())
        context.outline(x, y, x + w, y + h, 0xFF08090C.toInt())
        context.outline(x + 2, y + 2, x + w - 2, y + h - 2, 0xFF35343D.toInt())
    }

    override fun extractRenderState(context: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, f: Float) {
        super.extractRenderState(context, mouseX, mouseY, f)

        val x = left()
        val y = top()
        val w = panelWidth()
        val h = panelHeight()
        val headerH = 52
        val sidebarW = sidebarWidth()

        // Dim the world, then draw the framed configuration window.
        context.fill(0, 0, width, height, 0xA0000000.toInt())
        drawPanel(context, x, y, w, h)

        // Header, closely matching the reference's thin title bar.
        context.fill(x + 3, y + 3, x + w - 3, y + headerH, 0xFF1E1E25.toInt())
        context.outline(x + 3, y + 3, x + w - 3, y + headerH, 0xFF08090C.toInt())

        val title = Component.literal("Nopo Mod").withStyle(ChatFormatting.BOLD)
        context.text(font, title, x + 18, y + 14, 0xFFF0F0F2.toInt())
        context.text(font, Component.literal("Configuration"), x + 18, y + 31, 0xFF9A98A2.toInt())
        context.text(font, Component.literal("ESC"), x + w - 28, y + 18, 0xFF686670.toInt())

        // Left category column.
        val sideX = x + 3
        val sideY = y + headerH
        context.fill(sideX, sideY, sideX + sidebarW, y + h - 3, 0xFF121319.toInt())
        context.fill(sideX + sidebarW - 1, sideY, sideX + sidebarW, y + h - 3, 0xFF34333B.toInt())

        context.text(
            font,
            Component.literal("Categories").withStyle(ChatFormatting.BOLD),
            sideX + 20,
            sideY + 16,
            0xFFC8A6FF.toInt()
        )

        var cy = sideY + 44
        for (category in categories) {
            val selected = category.equals(currentCategory, true)
            val itemX = sideX + 16
            val itemW = sidebarW - 32
            if (selected) {
                context.fill(itemX, cy - 3, itemX + itemW, cy + 22, 0xFF211B2C.toInt())
                context.fill(itemX, cy - 3, itemX + 2, cy + 22, 0xFFC47CFF.toInt())
            }
            val categoryText = Component.literal(category)
                .withStyle(if (selected) ChatFormatting.UNDERLINE else ChatFormatting.RESET)
            context.text(
                font,
                categoryText,
                itemX + 14,
                cy + 3,
                if (selected) 0xFFE1B8FF.toInt() else 0xFFC0BEC7.toInt()
            )
            cy += 29
            if (cy > y + h - 28) break
        }

        // Main content area.
        val mainX = sideX + sidebarW + 18
        val mainY = sideY + 14
        val mainW = w - sidebarW - 39
        val contentBottom = y + h - 18
        val features = featuresFor(currentCategory)

        // Category heading.
        val heading = Component.literal(currentCategory ?: "Configuration").withStyle(ChatFormatting.BOLD)
        context.text(font, heading, mainX + 8, mainY + 5, 0xFFE0D7E8.toInt())
        context.fill(mainX + 8, mainY + 21, mainX + mainW - 8, mainY + 22, 0xFF383640.toInt())

        val listY = mainY + 31
        val cardHeight = 92
        val gap = 10
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
                val cardX = mainX + 8
                val cardW = mainW - 16

                // Layered borders/background reproduce the darker framed reference UI.
                context.fill(cardX, fy, cardX + cardW, fy + cardHeight, 0xFF1B1A21.toInt())
                context.outline(cardX, fy, cardX + cardW, fy + cardHeight, if (inside) 0xFF5B5363.toInt() else 0xFF35333C.toInt())
                context.fill(cardX + 2, fy + 2, cardX + cardW - 2, fy + 4, if (enabled) 0xFF57396C.toInt() else 0xFF282630.toInt())

                val title = feature.configData?.name ?: Component.literal(feature.moduleName)
                context.text(
                    font,
                    title.copy().withStyle(ChatFormatting.BOLD),
                    cardX + 14,
                    fy + 12,
                    0xFFE4E1E7.toInt()
                )

                feature.configData?.description?.let {
                    context.text(font, it, cardX + 14, fy + 34, 0xFF97949E.toInt())
                }

                // Toggle, styled like the reference's small bordered controls.
                val toggleW = 64
                val toggleH = 23
                val toggleX = cardX + cardW - toggleW - 12
                val toggleY = fy + 10
                context.fill(toggleX - 1, toggleY - 1, toggleX + toggleW + 1, toggleY + toggleH + 1, 0xFF090A0E.toInt())
                context.fill(toggleX, toggleY, toggleX + toggleW, toggleY + toggleH, if (enabled) 0xFF2A855F.toInt() else 0xFF292830.toInt())
                context.text(
                    font,
                    Component.literal(if (enabled) "ON" else "OFF"),
                    toggleX + if (enabled) 20 else 17,
                    toggleY + 7,
                    0xFFF5F2F6.toInt()
                )

                if (feature.config is PositionConfig && feature is GuiRendering) {
                    val moveX = cardX + cardW - toggleW - 12
                    val moveY = fy + 57
                    val moveHovered = mouseX >= moveX && mouseX <= moveX + toggleW && mouseY >= moveY && mouseY <= moveY + 20
                    if (moveHovered) {
                        hoverFeature = feature
                        hoverMove = true
                    }
                    context.fill(moveX - 1, moveY - 1, moveX + toggleW + 1, moveY + 21, 0xFF090A0E.toInt())
                    context.fill(moveX, moveY, moveX + toggleW, moveY + 20, if (moveHovered) 0xFF3A3042.toInt() else 0xFF25232B.toInt())
                    context.text(font, Component.literal("MOVE"), moveX + 17, moveY + 6, 0xFFD2CBD7.toInt())
                }
            }
            fy += cardHeight + gap
        }

        if (features.isEmpty()) {
            context.text(font, Component.literal("No features in this category"), mainX + 14, listY + 12, 0xFF85818B.toInt())
        }

        if (maxScroll > 0) {
            val trackX = x + w - 13
            context.fill(trackX, listY, trackX + 4, contentBottom, 0xFF292730.toInt())
            val trackH = contentBottom - listY
            val thumbHeight = max(24, trackH * trackH / (trackH + maxScroll))
            val thumbY = listY + ((trackH - thumbHeight) * scroll / maxScroll)
            context.fill(trackX, thumbY, trackX + 4, thumbY + thumbHeight, 0xFF746D7D.toInt())
        }

        if (hoverFeature != null) {
            val hint = if (hoverMove) "Click MOVE to open the position editor" else "Click a feature to toggle it"
            context.text(font, Component.literal(hint), mainX + 10, y + h - 13, 0xFF716D77.toInt())
        }
    }

    override fun mouseClicked(mouseEvent: MouseButtonEvent, bl: Boolean): Boolean {
        val mouseX = mouseEvent.x().toInt()
        val mouseY = mouseEvent.y().toInt()
        val x = left()
        val y = top()
        val w = panelWidth()
        val h = panelHeight()
        val headerH = 52
        val sidebarW = sidebarWidth()

        if (mouseX < x || mouseX > x + w || mouseY < y || mouseY > y + h) return super.mouseClicked(mouseEvent, bl)
        if (mouseY < y + headerH) return true

        val sideX = x + 3
        if (mouseX >= sideX && mouseX < sideX + sidebarW) {
            var cy = y + headerH + 44
            for (category in categories) {
                if (mouseY >= cy - 3 && mouseY <= cy + 22) {
                    currentCategory = category
                    scroll = 0
                    return true
                }
                cy += 29
                if (cy > y + h - 28) break
            }
            return true
        }

        val mainX = sideX + sidebarW + 18
        val mainW = w - sidebarW - 39
        val mainY = y + headerH + 14
        val listY = mainY + 31
        val cardHeight = 92
        val gap = 10
        val features = featuresFor(currentCategory)
        val visibleHeight = y + h - 18 - listY
        val maxScroll = max(0, features.size * (cardHeight + gap) - visibleHeight)
        scroll = scroll.coerceIn(0, maxScroll)

        val index = ((mouseY - listY + scroll) / (cardHeight + gap))
        if (index < 0 || index >= features.size) return true

        val feature = features[index]
        val fy = listY + index * (cardHeight + gap) - scroll
        val cardX = mainX + 8
        val cardW = mainW - 16
        if (mouseY < fy || mouseY > fy + cardHeight || mouseX < cardX || mouseX > cardX + cardW) return true

        val toggleW = 64
        val moveX = cardX + cardW - toggleW - 12
        if (feature.config is PositionConfig && feature is GuiRendering) {
            val moveY = fy + 57
            if (mouseX >= moveX && mouseX <= moveX + toggleW && mouseY >= moveY && mouseY <= moveY + 20) {
                NopoMod.screenToOpen = GuiEditor((feature.config as PositionConfig).pos) { context -> feature.doRender(context) }
                return true
            }
        }

        feature.config.enabled = !feature.config.enabled
        ConfigManager.save()
        return true
    }

    override fun mouseScrolled(d: Double, e: Double, scrollX: Double, scrollY: Double): Boolean {
        scroll = (scroll - (scrollY * 24).toInt()).coerceIn(0, Int.MAX_VALUE)
        return true
    }

    override fun isPauseScreen(): Boolean = false
}

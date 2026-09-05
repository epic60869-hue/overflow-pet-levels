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
 * Nopo's configuration screen, redesigned around the same UX principles as
 * modern SkyBlock mod menus: category navigation on the left and compact
 * feature cards on the right.
 *
 * This is intentionally Nopo's own implementation rather than a dependency
 * on SkyHanni.
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

    private fun left(): Int = max(18, width / 2 - 430)
    private fun top(): Int = max(18, height / 2 - 230)
    private fun panelWidth(): Int = min(860, width - 36)
    private fun panelHeight(): Int = min(460, height - 36)
    private fun sidebarWidth(): Int = 150

    override fun extractRenderState(context: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, f: Float) {
        super.extractRenderState(context, mouseX, mouseY, f)

        val x = left()
        val y = top()
        val w = panelWidth()
        val h = panelHeight()

        context.fill(0, 0, width, height, 0xB0000000.toInt())
        context.fill(x, y, x + w, y + h, 0xFF11151B.toInt())
        context.outline(x, y, x + w, y + h, 0xFF343B45.toInt())

        // Header
        context.fill(x, y, x + w, y + 48, 0xFF181E26.toInt())
        context.text(font, Component.literal("Nopo Mod").withStyle(ChatFormatting.BOLD), x + 18, y + 12, 0xFFE8FFF8.toInt())
        context.text(font, Component.literal("Configuration").withStyle(ChatFormatting.GRAY), x + 18, y + 28, 0xFF8B949E.toInt())
        context.text(font, Component.literal("ESC").withStyle(ChatFormatting.DARK_GRAY), x + w - 28, y + 18, 0xFF8B949E.toInt())

        // Sidebar
        context.fill(x, y + 48, x + sidebarWidth(), y + h, 0xFF0D1117.toInt())
        var cy = y + 62
        for (category in categories) {
            val selected = category.equals(currentCategory, true)
            if (selected) context.fill(x + 8, cy - 4, x + sidebarWidth() - 8, cy + 20, 0xFF18382F.toInt())
            context.text(
                font,
                Component.literal(category),
                x + 18,
                cy + 2,
                if (selected) 0xFF55E6B1.toInt() else 0xFFB7BEC8.toInt()
            )
            cy += 28
            if (cy > y + h - 22) break
        }

        val mainX = x + sidebarWidth() + 20
        val mainY = y + 60
        val mainW = w - sidebarWidth() - 35
        val features = featuresFor(currentCategory)
        val cardHeight = 66
        val gap = 9
        val contentBottom = y + h - 14
        val maxScroll = max(0, features.size * (cardHeight + gap) - (contentBottom - mainY))
        scroll = scroll.coerceIn(0, maxScroll)

        hoverFeature = null
        hoverMove = false
        var fy = mainY - scroll
        for (feature in features) {
            if (fy + cardHeight >= mainY && fy <= contentBottom) {
                val inside = mouseX >= mainX && mouseX <= mainX + mainW && mouseY >= fy && mouseY <= fy + cardHeight
                if (inside) hoverFeature = feature
                val enabled = feature.config.enabled

                context.fill(mainX, fy, mainX + mainW, fy + cardHeight, if (inside) 0xFF202832.toInt() else 0xFF1A2028.toInt())
                context.outline(mainX, fy, mainX + mainW, fy + cardHeight, 0xFF303944.toInt())
                if (enabled) context.fill(mainX, fy, mainX + 3, fy + cardHeight, 0xFF35D69B.toInt())

                val title = feature.configData?.name ?: Component.literal(feature.moduleName)
                context.text(font, title, mainX + 14, fy + 10, 0xFFF2F5F7.toInt())
                feature.configData?.description?.let {
                    context.text(font, it, mainX + 14, fy + 28, 0xFF8E98A5.toInt())
                }

                val toggleX = mainX + mainW - 72
                val toggleY = fy + 10
                context.fill(toggleX, toggleY, toggleX + 56, toggleY + 22, if (enabled) 0xFF1E8062.toInt() else 0xFF343B44.toInt())
                context.text(font, Component.literal(if (enabled) "ON" else "OFF"), toggleX + 18, toggleY + 7, 0xFFFFFFFF.toInt())

                if (feature.config is PositionConfig && feature is GuiRendering) {
                    val moveX = mainX + mainW - 72
                    val moveY = fy + 38
                    val moveHovered = mouseX >= moveX && mouseX <= moveX + 56 && mouseY >= moveY && mouseY <= moveY + 20
                    if (moveHovered) {
                        hoverFeature = feature
                        hoverMove = true
                    }
                    context.fill(moveX, moveY, moveX + 56, moveY + 20, if (moveHovered) 0xFF3A4652.toInt() else 0xFF29313A.toInt())
                    context.text(font, Component.literal("MOVE"), moveX + 13, moveY + 6, 0xFFBFD0DD.toInt())
                }
            }
            fy += cardHeight + gap
        }

        if (features.isEmpty()) {
            context.text(font, Component.literal("No features in this category"), mainX + 8, mainY + 10, 0xFF8E98A5.toInt())
        }

        if (maxScroll > 0) {
            val trackX = x + w - 8
            context.fill(trackX, mainY, trackX + 3, contentBottom, 0xFF242B33.toInt())
            val thumbHeight = max(20, (contentBottom - mainY) * (contentBottom - mainY) / (contentBottom - mainY + maxScroll))
            val thumbY = mainY + ((contentBottom - mainY - thumbHeight) * scroll / maxScroll)
            context.fill(trackX, thumbY, trackX + 3, thumbY + thumbHeight, 0xFF65717E.toInt())
        }

        if (hoverFeature != null) {
            val hint = if (hoverMove) "Click MOVE to position this overlay" else "Click the card to toggle this feature"
            context.text(font, Component.literal(hint), x + 170, y + h - 14, 0xFF6F7B87.toInt())
        }
    }

    override fun mouseClicked(mouseEvent: MouseButtonEvent, bl: Boolean): Boolean {
        val mouseX = mouseEvent.x().toInt()
        val mouseY = mouseEvent.y().toInt()
        val x = left()
        val y = top()
        val w = panelWidth()
        val h = panelHeight()

        if (mouseX < x || mouseX > x + w || mouseY < y || mouseY > y + h) return super.mouseClicked(mouseEvent, bl)
        if (mouseY < y + 48) return true

        // Category navigation.
        if (mouseX >= x && mouseX < x + sidebarWidth()) {
            var cy = y + 62
            for (category in categories) {
                if (mouseY >= cy - 4 && mouseY <= cy + 22) {
                    currentCategory = category
                    scroll = 0
                    return true
                }
                cy += 28
                if (cy > y + h - 22) break
            }
            return true
        }

        val mainX = x + sidebarWidth() + 20
        val mainW = w - sidebarWidth() - 35
        val mainY = y + 60
        val features = featuresFor(currentCategory)
        val cardHeight = 66
        val gap = 9
        val maxScroll = max(0, features.size * (cardHeight + gap) - (y + h - 14 - mainY))
        scroll = scroll.coerceIn(0, maxScroll)
        val index = ((mouseY - mainY + scroll) / (cardHeight + gap))
        if (index < 0 || index >= features.size) return true
        val feature = features[index]
        val fy = mainY + index * (cardHeight + gap) - scroll
        if (mouseY < fy || mouseY > fy + cardHeight || mouseX < mainX || mouseX > mainX + mainW) return true

        val moveX = mainX + mainW - 72
        val moveY = fy + 38
        if (feature.config is PositionConfig && feature is GuiRendering && mouseX >= moveX && mouseX <= moveX + 56 && mouseY >= moveY && mouseY <= moveY + 20) {
            NopoMod.screenToOpen = GuiEditor((feature.config as PositionConfig).pos) { context -> feature.doRender(context) }
            return true
        }

        feature.config.enabled = !feature.config.enabled
        ConfigManager.save()
        return true
    }

    override fun mouseScrolled(d: Double, e: Double, scrollX: Double, scrollY: Double): Boolean {
        scroll = (scroll - (scrollY * 24).toInt()).coerceAtLeast(0)
        return true
    }

    override fun isPauseScreen(): Boolean = false
}

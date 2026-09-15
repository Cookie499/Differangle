package net.astrorbits.differangle.camera

import net.minecraft.ChatFormatting
import net.minecraft.network.chat.ClickEvent
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.HoverEvent
import net.minecraft.network.chat.MutableComponent
import net.minecraft.network.chat.Style

/**
 * Chat affordances shared by the world commands and the client-only commands.
 *
 * Every clickable token carries its click action plus a hover tooltip, so the chat line documents itself
 * without the player having to learn an extra syntax. Labels and tooltips are passed in as components so
 * the callers can keep all player-facing text in translation keys. Click actions are limited to 256
 * characters of command text by vanilla; every command built here stays far below that.
 */
object Clickable {
    /** A coloured token with a hover tooltip and a click action. */
    fun token(label: Component, color: ChatFormatting, tooltip: Component, click: ClickEvent? = null): MutableComponent =
        label.copy().withStyle { style ->
            (if (click == null) style else style.withClickEvent(click))
                .withHoverEvent(HoverEvent.ShowText(tooltip))
                .withColor(color)
        }

    fun copy(label: Component, text: String, color: ChatFormatting, tooltip: Component): MutableComponent =
        token(label, color, tooltip, ClickEvent.CopyToClipboard(text))

    fun command(label: Component, command: String, color: ChatFormatting, tooltip: Component): MutableComponent =
        token(label, color, tooltip, ClickEvent.RunCommand(command))

    /** Joins tokens with a single space; the base style keeps later colours from bleeding into separators. */
    fun line(vararg parts: Component): MutableComponent {
        val line = Component.empty().withStyle(Style.EMPTY)
        parts.forEachIndexed { index, part ->
            if (index > 0) line.append(Component.literal(" ").withStyle(ChatFormatting.WHITE))
            line.append(part)
        }
        return line
    }
}

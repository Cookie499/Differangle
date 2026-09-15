package net.astrorbits.differangle

import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.builder.ArgumentBuilder
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.builder.RequiredArgumentBuilder
import com.mojang.brigadier.tree.CommandNode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Documents how the client command tree reaches the vanilla client dispatcher used for tab completion.
 *
 * Fabric copies the client command tree into the dispatcher that the server command tree built
 * (`ClientCommandInternals.addCommands` -> `copyChildren`): it adds the copy of a root child to the target
 * and only then fills the copy with its children. When the mod's client root shares its name with a server
 * command root, Brigadier merges the (still childless) copy into the existing node, so the whole client
 * subtree is orphaned and never shows up in suggestions.
 */
class ClientCommandMergeTest {
    private fun literal(name: String) = LiteralArgumentBuilder.literal<Any>(name)

    /** The server command tree: /differangle camera <greedy> | screen <greedy>. */
    private fun serverDispatcher(): CommandDispatcher<Any> {
        val dispatcher = CommandDispatcher<Any>()
        dispatcher.register(
            literal("differangle")
                .then(literal("camera").then(RequiredArgumentBuilder.argument<Any, String>("options", StringArgumentType.greedyString())))
                .then(literal("screen").then(RequiredArgumentBuilder.argument<Any, String>("options", StringArgumentType.greedyString())))
        )
        return dispatcher
    }

    /** The client command tree: /differangle camera|screen <forwarded> plus the client-only debug subcommands. */
    private fun clientDispatcher(): CommandDispatcher<Any> {
        val dispatcher = CommandDispatcher<Any>()
        dispatcher.register(
            literal("differangle").executes { 1 }
                .then(literal("camera").then(RequiredArgumentBuilder.argument<Any, String>("worldOptions", StringArgumentType.greedyString())))
                .then(literal("screen").then(RequiredArgumentBuilder.argument<Any, String>("worldOptions", StringArgumentType.greedyString())))
                .then(literal("mode").then(literal("texture")).then(literal("embedded")))
                .then(literal("layer").then(literal("terrain")))
                .then(literal("status"))
                .then(literal("list"))
                .then(literal("preview").then(literal("demo")).then(literal("clear")))
        )
        return dispatcher
    }

    /** Mirrors Fabric's copyChildren: add the copy first, then recurse into it. */
    private fun copyLikeFabric(origin: CommandNode<Any>, target: CommandNode<Any>) {
        val originalToCopy = HashMap<CommandNode<Any>, CommandNode<Any>>()
        originalToCopy[origin] = target
        copyChildrenLikeFabric(origin, target, originalToCopy)
    }

    private fun copyChildrenLikeFabric(
        origin: CommandNode<Any>,
        target: CommandNode<Any>,
        originalToCopy: MutableMap<CommandNode<Any>, CommandNode<Any>>,
    ) {
        for (child in origin.children) {
            @Suppress("UNCHECKED_CAST")
            val builder = child.createBuilder() as ArgumentBuilder<Any, *>
            builder.requires { true }
            if (builder.command != null) builder.executes { 0 }
            val result = builder.build()
            originalToCopy[child] = result
            target.addChild(result)
            if (child.children.isNotEmpty()) copyChildrenLikeFabric(child, result, originalToCopy)
        }
    }

    /** Builds the complete copy before it is merged into the target. */
    private fun copyWholeTree(node: CommandNode<Any>): CommandNode<Any> {
        @Suppress("UNCHECKED_CAST")
        val builder = node.createBuilder() as ArgumentBuilder<Any, *>
        builder.requires { true }
        if (builder.command != null) builder.executes { 0 }
        val result = builder.build()
        for (child in node.children) result.addChild(copyWholeTree(child))
        return result
    }

    private fun names(node: CommandNode<Any>) = node.children.map { it.name }.sorted()

    @Test
    fun `copying a colliding client root after the fact loses its subtree`() {
        val server = serverDispatcher()
        val client = clientDispatcher()
        copyLikeFabric(client.root, server.root)
        assertEquals(listOf("camera", "screen"), names(server.root.getChild("differangle")), "client-only subcommands survive the merge")
    }

    @Test
    fun `merging a complete client subtree keeps the client-only subcommands`() {
        val server = serverDispatcher()
        val client = clientDispatcher()
        for (child in client.root.children) server.root.addChild(copyWholeTree(child))
        val merged = names(server.root.getChild("differangle"))
        assertTrue(
            merged.containsAll(listOf("camera", "screen", "mode", "layer", "status", "list", "preview")),
            "merged children=$merged",
        )
    }
}

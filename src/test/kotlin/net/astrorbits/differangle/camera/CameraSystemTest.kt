package net.astrorbits.differangle.camera

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class CameraSystemTest {
    private class Target : CameraTarget {
        var closes = 0
        override fun close() { closes++ }
    }

    private class Backend : CameraBackend<Target> {
        val targets = mutableListOf<Target>()
        val renders = mutableListOf<String>()
        val draws = mutableListOf<Pair<String, Target>>()
        var duringRender: () -> Unit = {}
        override fun createTarget(camera: CameraDefinition) = Target().also(targets::add)
        override fun renderCamera(camera: CameraDefinition, target: Target) {
            renders += camera.id
            duringRender()
        }
        override fun renderScreen(screen: ScreenDefinition, frame: CameraFrame<Target>, mainOrigin: Position) {
            draws += screen.id to frame.target
        }
    }

    @Test fun `twenty screens share one render and reuse it until the FPS deadline`() {
        val backend = Backend()
        val system = CameraSystem(backend)
        val camera = CameraDefinition("a")
        system.putCamera(camera)
        val ids = (1..20).map { "screen$it" }
        ids.forEach { system.putScreen(ScreenDefinition(it, "a")) }
        assertEquals(FrameStatistics(1, 20, 1), system.renderFrame(0, ids + ids, Position()))
        assertEquals(FrameStatistics(0, 20, 1), system.renderFrame(camera.intervalNanos - 1, ids, Position()))
        assertEquals(FrameStatistics(1, 20, 1), system.renderFrame(camera.intervalNanos, ids, Position()))
        assertEquals(1, backend.targets.size)
        assertTrue(backend.draws.all { it.second === backend.targets.single() })
    }

    @Test fun `budget one gives all cameras a turn even with unequal priority`() {
        val backend = Backend()
        val system = CameraSystem(backend)
        val ids = listOf("a", "b", "c", "d")
        ids.forEachIndexed { i, id ->
            system.putCamera(CameraDefinition(id, priority = -i))
            system.putScreen(ScreenDefinition(id, id))
        }
        repeat(8) { system.renderFrame(it * 1_000_000_000L, ids, Position()) }
        assertEquals(ids + ids, backend.renders)
    }

    @Test fun `invisible disabled and unknown screens never cause rendering`() {
        val backend = Backend()
        val system = CameraSystem(backend)
        system.putCamera(CameraDefinition("a"))
        system.putCamera(CameraDefinition("b", enabled = false))
        system.putScreen(ScreenDefinition("hidden", "a"))
        system.putScreen(ScreenDefinition("disabled", "a", enabled = false))
        system.putScreen(ScreenDefinition("b", "b"))
        assertEquals(FrameStatistics(0, 0, 0), system.renderFrame(0, listOf("disabled", "b", "missing"), Position()))
        system.renderFrame(1, listOf("hidden"), Position())
        val cached = system.frame("a")
        system.renderFrame(1_000_000_000, emptyList(), Position())
        assertSame(cached, system.frame("a"))
        assertEquals(listOf("a"), backend.renders)
    }

    @Test fun `camera changes invalidate the target and cascade removal clears screens`() {
        val backend = Backend()
        val system = CameraSystem(backend)
        val camera = CameraDefinition("a")
        system.putCamera(camera)
        system.putScreen(ScreenDefinition("s", "a"))
        system.renderFrame(0, listOf("s"), Position())
        system.putCamera(camera)
        assertEquals(0, backend.targets[0].closes)
        system.putCamera(camera.copy(resolution = Resolution(512, 288), fov = 100f))
        assertNull(system.frame("a"))
        assertEquals(1, backend.targets[0].closes)
        system.renderFrame(1, listOf("s"), Position())
        system.removeCamera("a")
        assertTrue(system.screens().isEmpty())
        assertEquals(listOf(1, 1), backend.targets.map { it.closes })
        system.clear()
        assertEquals(listOf(1, 1), backend.targets.map { it.closes })
    }

    @Test fun `last screen removal or rebinding releases its old camera resources`() {
        val backend = Backend()
        val system = CameraSystem(backend)
        system.putCamera(CameraDefinition("a"))
        system.putCamera(CameraDefinition("b"))
        system.putScreen(ScreenDefinition("s1", "a"))
        system.putScreen(ScreenDefinition("s2", "a"))
        system.renderFrame(0, listOf("s1"), Position())
        system.removeScreen("s1")
        assertNotNull(system.frame("a"))
        system.putScreen(ScreenDefinition("s2", "b"))
        assertNull(system.frame("a"))
        assertEquals(1, backend.targets.single().closes)
        assertThrows(IllegalArgumentException::class.java) { system.putScreen(ScreenDefinition("bad", "missing")) }
        assertEquals(listOf("s2"), system.screens().map { it.id })
    }

    @Test fun `failed refresh discards partial output and can retry without waiting`() {
        val backend = Backend()
        val system = CameraSystem(backend)
        system.putCamera(CameraDefinition("a"))
        system.putScreen(ScreenDefinition("s", "a"))
        system.renderFrame(0, listOf("s"), Position())
        backend.duringRender = { error("draw failed") }
        assertThrows(IllegalStateException::class.java) { system.renderFrame(1_000_000_000, listOf("s"), Position()) }
        assertNull(system.frame("a"))
        assertEquals(1, backend.targets.single().closes)
        assertEquals(1, backend.draws.size)
        backend.duringRender = {}
        assertEquals(1, system.renderFrame(1_000_000_001, listOf("s"), Position()).cameraUpdates)
    }

    @Test fun `recursive rendering and mutation are rejected and guard recovers`() {
        val backend = Backend()
        val system = CameraSystem(backend)
        system.putCamera(CameraDefinition("a"))
        system.putScreen(ScreenDefinition("s", "a"))
        backend.duringRender = {
            assertThrows(IllegalStateException::class.java) { system.clear() }
            system.renderFrame(1, listOf("s"), Position())
        }
        assertThrows(IllegalStateException::class.java) { system.renderFrame(0, listOf("s"), Position()) }
        backend.duringRender = {}
        assertEquals(1, system.renderFrame(1, listOf("s"), Position()).cameraUpdates)
    }

    @Test fun `clear releases all frames and resets the world and clock`() {
        val backend = Backend()
        val system = CameraSystem(backend, maxUpdatesPerFrame = 2)
        listOf("a", "b").forEach {
            system.putCamera(CameraDefinition(it))
            system.putScreen(ScreenDefinition(it, it))
        }
        system.renderFrame(100, listOf("a", "b"), Position())
        assertThrows(IllegalArgumentException::class.java) { system.renderFrame(99, emptyList(), Position()) }
        system.clear()
        system.clear()
        assertEquals(listOf(1, 1), backend.targets.map { it.closes })
        assertTrue(system.cameras().isEmpty())
        assertTrue(system.screens().isEmpty())
        assertEquals(FrameStatistics(0, 0, 0), system.renderFrame(0, emptyList(), Position()))
    }

    @Test fun `allocation failure resets guard and permits retry`() {
        val recording = Backend()
        var fail = true
        val backend = object : CameraBackend<Target> by recording {
            override fun createTarget(camera: CameraDefinition): Target {
                if (fail) error("allocation failed")
                return recording.createTarget(camera)
            }
        }
        val system = CameraSystem(backend)
        system.putCamera(CameraDefinition("a"))
        system.putScreen(ScreenDefinition("s", "a"))
        assertThrows(IllegalStateException::class.java) { system.renderFrame(0, listOf("s"), Position()) }
        assertNull(system.frame("a"))
        fail = false
        assertEquals(1, system.renderFrame(1, listOf("s"), Position()).cameraUpdates)
    }

    @Test fun `surface failure preserves valid frame and resets render guard`() {
        val recording = Backend()
        var fail = true
        val backend = object : CameraBackend<Target> by recording {
            override fun renderScreen(screen: ScreenDefinition, frame: CameraFrame<Target>, mainOrigin: Position) {
                if (fail) error("surface failed")
                recording.renderScreen(screen, frame, mainOrigin)
            }
        }
        val system = CameraSystem(backend)
        system.putCamera(CameraDefinition("a"))
        system.putScreen(ScreenDefinition("s", "a"))
        assertThrows(IllegalStateException::class.java) { system.renderFrame(0, listOf("s"), Position()) }
        assertNotNull(system.frame("a"))
        fail = false
        assertEquals(FrameStatistics(0, 1, 1), system.renderFrame(1, listOf("s"), Position()))
    }

    @Test fun `clear attempts every resource even if some releases fail`() {
        var closes = 0
        val backend = object : CameraBackend<CameraTarget> {
            override fun createTarget(camera: CameraDefinition) = object : CameraTarget {
                override fun close() { closes++; error("cleanup ${camera.id}") }
            }
            override fun renderCamera(camera: CameraDefinition, target: CameraTarget) {}
            override fun renderScreen(screen: ScreenDefinition, frame: CameraFrame<CameraTarget>, mainOrigin: Position) {}
        }
        val system = CameraSystem(backend, 2)
        listOf("a", "b").forEach {
            system.putCamera(CameraDefinition(it))
            system.putScreen(ScreenDefinition(it, it))
        }
        system.renderFrame(0, listOf("a", "b"), Position())
        val error = assertThrows(IllegalStateException::class.java) { system.clear() }
        assertEquals(1, error.suppressed.size)
        assertEquals(2, closes)
        assertTrue(system.cameras().isEmpty())
        assertNull(system.frame("a"))
        system.clear()
        assertEquals(2, closes)
    }
}

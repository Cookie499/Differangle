package net.astrorbits.differangle.camera

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ScreenFacingTest {
    @Test fun `front back and edge remain consistent after arbitrary rotations`() {
        val q=Rotation.minecraftDegrees(37f,52f,81f)
        val center=Position(30_000_000.0,100.0,-30_000_000.0)
        val screen=ScreenDefinition("s","c",center,q)
        val normal=q.quaternion().transform(org.joml.Vector3d(0.0,0.0,5.0))
        assertTrue(screen.isFrontFacing(Position(center.x+normal.x,center.y+normal.y,center.z+normal.z)))
        assertFalse(screen.isFrontFacing(Position(center.x-normal.x,center.y-normal.y,center.z-normal.z)))
        assertFalse(screen.isFrontFacing(center))
    }
}

package net.astrorbits.differangle.client.world

import com.mojang.blaze3d.pipeline.RenderTarget
import net.astrorbits.differangle.camera.Position
import net.astrorbits.differangle.client.render.CameraCompositor
import net.astrorbits.differangle.world.ScreenBlockEntity
import org.joml.*

/** Constant-material housing is independent of Camera Targets, including missing/disabled cameras. */
object WorldGeometry {
    fun housing(block: ScreenBlockEntity,origin: Position,view: Matrix4f,target: RenderTarget,gpu: CameraCompositor) {
        val c=block.config
        val screen=WorldClient.definition(block)
        val model=Matrix4f().translation((screen.position.x-origin.x).toFloat(),(screen.position.y-origin.y).toFloat(),(screen.position.z-origin.z).toFloat()).rotate(screen.rotation.quaternion())
        val gray=Vector4f(.09f,.10f,.12f,1f)
        val border=.04f
        cube(Matrix4f(model).translate(0f,0f,-c.frameDepth/2-.002f).scale(c.width,c.height,c.frameDepth),view,target,gpu,gray)
        for (side in listOf(-1f,1f)) {
            cube(Matrix4f(model).translate(side*(c.width+border)/2,0f,-c.frameDepth/2).scale(border,c.height+2*border,c.frameDepth),view,target,gpu,gray)
            cube(Matrix4f(model).translate(0f,side*(c.height+border)/2,-c.frameDepth/2).scale(c.width,border,c.frameDepth),view,target,gpu,gray)
        }
        val start=WorldClient.baseRotation(block).transform(Vector3d(0.0,0.0,.1)).add(WorldClient.anchor(block))
        val end=screen.rotation.quaternion().transform(Vector3d(0.0,0.0,-c.frameDepth.toDouble())).add(screen.position.x,screen.position.y,screen.position.z)
        line(start,end,.045f,origin,view,target,gpu,Vector4f(.15f,.16f,.18f,1f))
    }
    fun debug(origin: Position,view: Matrix4f,target: RenderTarget,gpu: CameraCompositor) {
        for (camera in WorldClient.debugCameras) {
            val center=Vector3d(camera.x,camera.y,camera.z)
            val q=camera.viewRotation.quaternion()
            val axes=listOf(Vector3d(.6,0.0,0.0) to Vector4f(1f,.1f,.1f,1f),Vector3d(0.0,.6,0.0) to Vector4f(.1f,1f,.1f,1f),Vector3d(0.0,0.0,-.6) to Vector4f(.1f,.4f,1f,1f))
            axes.forEach { (end,color) -> line(center,q.transform(end).add(center),.015f,origin,view,target,gpu,color) }
            val half=kotlin.math.tan(Math.toRadians(camera.fov.toDouble()/2)).coerceAtMost(3.0)
            val points=listOf(-1 to -1,1 to -1,1 to 1,-1 to 1).map { (x,y) -> q.transform(Vector3d(x*half*16/9,y*half,-1.0)).add(center) }
            points.forEachIndexed { i,p ->
                line(center,p,.008f,origin,view,target,gpu,Vector4f(.8f,.6f,.1f,1f))
                line(p,points[(i+1)%4],.008f,origin,view,target,gpu,Vector4f(.8f,.6f,.1f,1f))
            }
        }
    }
    private fun line(a: Vector3d,b: Vector3d,thickness: Float,origin: Position,view: Matrix4f,target: RenderTarget,gpu: CameraCompositor,color: Vector4f) {
        val delta=Vector3d(b).sub(a)
        val length=delta.length()
        if(length<1e-6) return
        val rotation=Quaternionf().rotationTo(Vector3f(0f,0f,1f),Vector3f(delta.normalize()))
        val midpoint=Vector3d(a).add(b).mul(.5)
        cube(Matrix4f().translation((midpoint.x-origin.x).toFloat(),(midpoint.y-origin.y).toFloat(),(midpoint.z-origin.z).toFloat())
            .rotate(rotation).scale(thickness,thickness,length.toFloat()),view,target,gpu,color)
    }
    private fun cube(matrix: Matrix4f,view: Matrix4f,target: RenderTarget,gpu: CameraCompositor,color: Vector4f) {
        val halfPi=(Math.PI/2).toFloat()
        val rotations=listOf(Quaternionf(),Quaternionf().rotationY(Math.PI.toFloat()),Quaternionf().rotationY(halfPi),Quaternionf().rotationY(-halfPi),Quaternionf().rotationX(halfPi),Quaternionf().rotationX(-halfPi))
        rotations.forEach { gpu.solid(Matrix4f(matrix).rotate(it).translate(0f,0f,.5f),view,target,color) }
    }
}

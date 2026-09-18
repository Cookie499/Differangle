package net.astrorbits.differangle.client.render;

import com.mojang.blaze3d.vertex.VertexConsumer;

/** Buffers font quads and reverses their winding without changing positions or texture coordinates. */
public final class ReverseQuadVertexConsumer implements VertexConsumer {
    private static final int[] REVERSED = { 0, 3, 2, 1 };
    private final VertexConsumer delegate;
    private final Vertex[] vertices = new Vertex[4];
    private int count;
    private Vertex current;

    public ReverseQuadVertexConsumer(VertexConsumer delegate) {
        this.delegate = delegate;
    }

    @Override public VertexConsumer addVertex(float x, float y, float z) {
        if (count == 4) flush();
        current = new Vertex(x, y, z);
        vertices[count++] = current;
        return this;
    }

    @Override public VertexConsumer setColor(int r, int g, int b, int a) {
        return setColor((a & 255) << 24 | (r & 255) << 16 | (g & 255) << 8 | b & 255);
    }
    @Override public VertexConsumer setColor(int color) { current.color = color; current.hasColor = true; return this; }
    @Override public VertexConsumer setUv(float u, float v) { current.u = u; current.v = v; current.hasUv = true; return this; }
    @Override public VertexConsumer setUv1(int u, int v) { current.u1 = u; current.v1 = v; current.hasUv1 = true; return this; }
    @Override public VertexConsumer setUv2(int u, int v) { current.u2 = u; current.v2 = v; current.hasUv2 = true; return this; }
    @Override public VertexConsumer setNormal(float x, float y, float z) { current.nx = x; current.ny = y; current.nz = z; current.hasNormal = true; return this; }
    @Override public VertexConsumer setLineWidth(float width) { current.lineWidth = width; current.hasLineWidth = true; return this; }

    public void flush() {
        if (count == 0) return;
        if (count != 4) throw new IllegalStateException("Incomplete font quad: " + count + " vertices");
        for (int index : REVERSED) emit(vertices[index]);
        count = 0;
        current = null;
    }

    private void emit(Vertex vertex) {
        VertexConsumer output = delegate.addVertex(vertex.x, vertex.y, vertex.z);
        if (vertex.hasColor) output.setColor(vertex.color);
        if (vertex.hasUv) output.setUv(vertex.u, vertex.v);
        if (vertex.hasUv1) output.setUv1(vertex.u1, vertex.v1);
        if (vertex.hasUv2) output.setUv2(vertex.u2, vertex.v2);
        if (vertex.hasNormal) output.setNormal(vertex.nx, vertex.ny, vertex.nz);
        if (vertex.hasLineWidth) output.setLineWidth(vertex.lineWidth);
    }

    private static final class Vertex {
        final float x, y, z;
        int color, u1, v1, u2, v2;
        float u, v, nx, ny, nz, lineWidth;
        boolean hasColor, hasUv, hasUv1, hasUv2, hasNormal, hasLineWidth;
        Vertex(float x, float y, float z) { this.x = x; this.y = y; this.z = z; }
    }
}

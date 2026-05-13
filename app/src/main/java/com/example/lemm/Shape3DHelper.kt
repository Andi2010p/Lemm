package com.example.lemm

import android.util.Log
import com.google.android.filament.*
import com.google.android.filament.RenderableManager.PrimitiveType
import dev.romainguy.kotlin.math.Float3
import io.github.sceneview.node.CubeNode
import io.github.sceneview.node.Node
import org.locationtech.jts.geom.Coordinate
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.IntBuffer

object Shape3DHelper {

    private fun allocateDirectFloatBuffer(data: FloatArray): FloatBuffer {
        return ByteBuffer.allocateDirect(data.size * 4).order(ByteOrder.nativeOrder())
            .asFloatBuffer().apply { put(data); position(0) }
    }

    private fun allocateDirectIntBuffer(data: IntArray): IntBuffer {
        return ByteBuffer.allocateDirect(data.size * 4).order(ByteOrder.nativeOrder())
            .asIntBuffer().apply { put(data); position(0) }
    }

    @JvmStatic
    fun createSunLight(engine: Engine): Node {
        val lightEntity = EntityManager.get().create()
        LightManager.Builder(LightManager.Type.SUN)
            .color(1.0f, 1.0f, 1.0f)
            .intensity(110000.0f)
            .direction(-0.5f, -0.8f, -1.0f)
            .castShadows(true)
            .build(engine, lightEntity)
        return Node(engine, lightEntity)
    }

    @JvmStatic
    fun createSolidWorksExtrusion(
        engine: Engine,
        coords: Array<Coordinate>,
        height: Float,
        scale: Float,
        cx: Float,
        cy: Float
    ): ExtrusionNode? {
        val n = coords.size - 1
        if (n < 3) return null

        val wallVerts = n * 2
        val totalVerts = n * 4

        val verts = FloatArray(totalVerts * 3)
        val tangents = FloatArray(totalVerts * 4)

        for (i in 0 until n) {
            val x = (coords[i].x.toFloat() * scale) - cx
            val y = (coords[i].y.toFloat() * scale) - cy
            verts[i * 6] = x; verts[i * 6 + 1] = y; verts[i * 6 + 2] = 0f
            verts[i * 6 + 3] = x; verts[i * 6 + 4] = y; verts[i * 6 + 5] = height
        }

        val bo = wallVerts * 3
        for (i in 0 until n) {
            verts[bo + i * 3] = (coords[i].x.toFloat() * scale) - cx
            verts[bo + i * 3 + 1] = (coords[i].y.toFloat() * scale) - cy
            verts[bo + i * 3 + 2] = 0f
        }

        val to = (wallVerts + n) * 3
        for (i in 0 until n) {
            verts[to + i * 3] = (coords[i].x.toFloat() * scale) - cx
            verts[to + i * 3 + 1] = (coords[i].y.toFloat() * scale) - cy
            verts[to + i * 3 + 2] = height
        }

        for (i in 0 until totalVerts) {
            tangents[i * 4] = 1.0f; tangents[i * 4 + 1] = 0.0f; tangents[i * 4 + 2] = 0.0f; tangents[i * 4 + 3] = 1.0f
        }

        val indices = IntArray(n * 6 + (n - 2) * 6)
        var idx = 0
        for (i in 0 until n) {
            val next = (i + 1) % n
            indices[idx++] = i * 2; indices[idx++] = next * 2; indices[idx++] = i * 2 + 1
            indices[idx++] = next * 2; indices[idx++] = next * 2 + 1; indices[idx++] = i * 2 + 1
        }
        for (i in 1 until n - 1) {
            indices[idx++] = wallVerts; indices[idx++] = wallVerts + i + 1; indices[idx++] = wallVerts + i
        }
        for (i in 1 until n - 1) {
            indices[idx++] = wallVerts + n; indices[idx++] = wallVerts + n + i; indices[idx++] = wallVerts + n + i + 1
        }

        val vBuf = allocateDirectFloatBuffer(verts)
        val tBuf = allocateDirectFloatBuffer(tangents)
        val iBuf = allocateDirectIntBuffer(indices)

        val vertexBuffer = VertexBuffer.Builder().bufferCount(2).vertexCount(totalVerts)
            .attribute(VertexBuffer.VertexAttribute.POSITION, 0, VertexBuffer.AttributeType.FLOAT3, 0, 12)
            .attribute(VertexBuffer.VertexAttribute.TANGENTS, 1, VertexBuffer.AttributeType.FLOAT4, 0, 16)
            .normalized(VertexBuffer.VertexAttribute.TANGENTS).build(engine)

        vertexBuffer.setBufferAt(engine, 0, vBuf)
        vertexBuffer.setBufferAt(engine, 1, tBuf)

        val indexBuffer = IndexBuffer.Builder().indexCount(indices.size).bufferType(IndexBuffer.Builder.IndexType.UINT).build(engine)
        indexBuffer.setBuffer(engine, iBuf)

        var material: MaterialInstance? = null
        val dummy = CubeNode(engine, size = Float3(0.01f))
        val instance = engine.renderableManager.getInstance(dummy.entity)
        if (instance != 0) material = engine.renderableManager.getMaterialInstanceAt(instance, 0)
        if (material == null) material = dummy.materialInstances.firstOrNull()

        // Give it SolidWorks Blue color
        material?.setParameter("baseColor", 0.1f, 0.5f, 0.8f, 1.0f)
        material?.setParameter("roughness", 0.4f)

        val box = Box(0f, 0f, height / 2f, 1000f, 1000f, 1000f)

        val entity = EntityManager.get().create()
        val builder = RenderableManager.Builder(1)
            .geometry(0, PrimitiveType.TRIANGLES, vertexBuffer, indexBuffer)
            .boundingBox(box)
            .culling(false)

        if (material != null) builder.material(0, material)
        builder.build(engine, entity)

        return ExtrusionNode(engine, entity, vertexBuffer, indexBuffer, listOf(vBuf, tBuf, iBuf))
    }

    class ExtrusionNode(
        engine: Engine,
        entity: Int,
        val vertexBuffer: VertexBuffer,
        val indexBuffer: IndexBuffer,
        val nioBuffers: List<java.nio.Buffer>
    ) : Node(engine, entity)
}
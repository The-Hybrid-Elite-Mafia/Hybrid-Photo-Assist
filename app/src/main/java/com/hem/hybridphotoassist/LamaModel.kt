package com.hem.hybridphotoassist

import org.tensorflow.lite.Interpreter
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/**
 * LaMa erase-and-fill model run on the phone's CPU with LiteRT.
 * Inputs: image [1,512,512,3] and mask [1,512,512,1], both 0..1. Output [1,512,512,3].
 * Which input is which is decided from the tensor shapes, not assumed from order.
 */
class LamaModel(modelFile: File, threads: Int = 4) : FillModel, AutoCloseable {
    private val interpreter: Interpreter
    private val imageIndex: Int
    private val maskIndex: Int
    override val size: Int

    init {
        val buffer = FileInputStream(modelFile).use { fis ->
            fis.channel.map(FileChannel.MapMode.READ_ONLY, 0, fis.channel.size())
        }
        interpreter = Interpreter(buffer, Interpreter.Options().setNumThreads(threads))
        require(interpreter.inputTensorCount == 2) { "fill model has ${interpreter.inputTensorCount} inputs, expected 2" }
        val s0 = interpreter.getInputTensor(0).shape()
        val s1 = interpreter.getInputTensor(1).shape()
        if (s0.last() == 3 && s1.last() == 1) { imageIndex = 0; maskIndex = 1 }
        else if (s0.last() == 1 && s1.last() == 3) { imageIndex = 1; maskIndex = 0 }
        else error("unexpected fill model inputs ${s0.contentToString()} / ${s1.contentToString()}")
        val shape = interpreter.getInputTensor(imageIndex).shape()
        require(shape.size == 4 && shape[1] == shape[2]) { "fill model input not square NHWC: ${shape.contentToString()}" }
        size = shape[1]
    }

    override fun fill(rgb: FloatArray, mask: FloatArray): FloatArray {
        val n = size * size
        require(rgb.size == n * 3 && mask.size == n) { "fill input size mismatch" }
        val img = ByteBuffer.allocateDirect(n * 3 * 4).order(ByteOrder.nativeOrder())
        img.asFloatBuffer().put(rgb)
        val msk = ByteBuffer.allocateDirect(n * 4).order(ByteOrder.nativeOrder())
        msk.asFloatBuffer().put(mask)
        val inputs = arrayOfNulls<Any>(2)
        inputs[imageIndex] = img
        inputs[maskIndex] = msk
        val out = ByteBuffer.allocateDirect(n * 3 * 4).order(ByteOrder.nativeOrder())
        interpreter.runForMultipleInputsOutputs(inputs, mapOf(0 to out))
        out.rewind()
        val result = FloatArray(n * 3)
        out.asFloatBuffer().get(result)
        return result
    }

    override fun close() = interpreter.close()
}

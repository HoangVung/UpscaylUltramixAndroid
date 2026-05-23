package com.vung.upscaylultramix

import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.GpuDelegate
import org.tensorflow.lite.nnapi.NnApiDelegate
import java.io.File

object RuntimeDelegateFactory {

    class InterpreterWrapper(
        val interpreter: Interpreter,
        private val nnApiDelegate: NnApiDelegate?,
        private val gpuDelegate: GpuDelegate?,
        val runtimeLabel: String
    ) : AutoCloseable {
        override fun close() {
            interpreter.close()
            nnApiDelegate?.close()
            gpuDelegate?.close()
        }
    }

    fun createInterpreter(modelFile: File, preferredRuntime: String): InterpreterWrapper {
        val normalizedRuntime = preferredRuntime.lowercase()

        return when (normalizedRuntime) {
            "nnapi" -> createNnapiInterpreterOrFallback(modelFile)
            "gpu" -> createGpuInterpreterOrFallback(modelFile)
            else -> createCpuInterpreter(modelFile)
        }
    }

    private fun createNnapiInterpreterOrFallback(modelFile: File): InterpreterWrapper {
        return try {
            val nnapi = NnApiDelegate()
            val options = Interpreter.Options().apply {
                addDelegate(nnapi)
            }
            val interpreter = Interpreter(modelFile, options)
            InterpreterWrapper(interpreter, nnapi, null, "NNAPI/NPU")
        } catch (e: Throwable) {
            createGpuInterpreterOrFallback(modelFile)
        }
    }

    private fun createGpuInterpreterOrFallback(modelFile: File): InterpreterWrapper {
        return try {
            val gpu = GpuDelegate()
            val options = Interpreter.Options().apply {
                addDelegate(gpu)
            }
            val interpreter = Interpreter(modelFile, options)
            InterpreterWrapper(interpreter, null, gpu, "GPU")
        } catch (e: Throwable) {
            createCpuInterpreter(modelFile)
        }
    }

    private fun createCpuInterpreter(modelFile: File): InterpreterWrapper {
        val options = Interpreter.Options()
        val interpreter = Interpreter(modelFile, options)
        return InterpreterWrapper(interpreter, null, null, "CPU fallback")
    }
}

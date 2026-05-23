package com.vung.upscaylultramix

import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.GpuDelegate
import org.tensorflow.lite.nnapi.NnApiDelegate
import java.io.File

object RuntimeDelegateFactory {

    class InterpreterWrapper(
        val interpreter: Interpreter,
        val delegate: AutoCloseable?,
        val runtimeLabel: String
    ) : AutoCloseable {
        override fun close() {
            interpreter.close()
            delegate?.close()
        }
    }

    fun createInterpreter(modelFile: File, preferredRuntime: String): InterpreterWrapper {
        val options = Interpreter.Options()
        var actualRuntime = "CPU fallback"
        var delegate: AutoCloseable? = null

        try {
            when (preferredRuntime.lowercase()) {
                "nnapi" -> {
                    try {
                        val nnapi = NnApiDelegate()
                        options.addDelegate(nnapi)
                        delegate = nnapi
                        actualRuntime = "NNAPI/NPU"
                    } catch (e: Throwable) {
                        // Fallback to GPU
                        return createInterpreter(modelFile, "gpu")
                    }
                }
                "gpu" -> {
                    try {
                        val gpu = GpuDelegate()
                        options.addDelegate(gpu)
                        delegate = gpu
                        actualRuntime = "GPU"
                    } catch (e: Throwable) {
                        // Fallback to CPU
                        return createInterpreter(modelFile, "cpu")
                    }
                }
                else -> {
                    // CPU fallback
                    actualRuntime = "CPU fallback"
                }
            }
            val interpreter = Interpreter(modelFile, options)
            return InterpreterWrapper(interpreter, delegate, actualRuntime)
        } catch (e: Throwable) {
            // If fallback fails, try CPU
            if (preferredRuntime != "cpu") {
                return createInterpreter(modelFile, "cpu")
            } else {
                throw e
            }
        }
    }
}

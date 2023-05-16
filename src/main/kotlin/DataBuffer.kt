import java.lang.IllegalArgumentException
import java.nio.ByteBuffer

class DataBuffer {
    private val srcBuffer = ByteBuffer.allocate(1024)
    private val destBuffer = ByteBuffer.allocate(1024)
    private var srcDataPresent = false
    private var destDataPresent = false

    fun borrowSrcForConsuming():ByteBuffer {
        if(!srcDataPresent) {
            throw IllegalArgumentException("Can't consume when data is not present")
        }
        srcDataPresent = false
        return srcBuffer
    }

    fun borrowDestForConsuming():ByteBuffer {
        if(!destDataPresent) {
            throw IllegalArgumentException("Can't consume when data is not present")
        }
        destDataPresent = false
        return destBuffer
    }

    fun borrowSrcForProducing():ByteBuffer {
        if(srcDataPresent) {
            throw IllegalArgumentException("Can't produce when data is already present")
        }
        srcDataPresent = true
        return srcBuffer
    }

    fun borrowDestForProducing():ByteBuffer {
        if(destDataPresent) {
            throw IllegalArgumentException("Can't produce when data is already present")
        }
        destDataPresent = true
        return destBuffer
    }

    fun srcHasData():Boolean {
        return srcDataPresent
    }

    fun destHasData():Boolean {
        return destDataPresent
    }
}
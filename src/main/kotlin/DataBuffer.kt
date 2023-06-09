import java.lang.IllegalArgumentException
import java.nio.ByteBuffer

data class DataBuffer(val srcBuffer:ByteBuffer, val destBuffer:ByteBuffer, private var srcHasData:Boolean = false) {
    private var destHasData = false

    fun markHasData(buffer:ByteBuffer) {
        mark(buffer, true)
    }

    fun markHasNoData(buffer:ByteBuffer) {
        mark(buffer, false)
    }

    fun hasData():Boolean {
        return srcHasData || destHasData
    }

    fun srcHasData():Boolean {
        return srcHasData
    }

    fun destHasData():Boolean {
        return destHasData
    }

    override fun toString():String {
        return "Data: srcBuffer: $srcBuffer destBuffer $destBuffer"
    }
    private fun mark(buffer:ByteBuffer, flag:Boolean) {
        if(buffer === srcBuffer) {
            srcHasData = flag
        } else if(buffer === destBuffer) {
            destHasData = flag
        }

    }
}
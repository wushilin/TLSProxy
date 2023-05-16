import org.apache.tomcat.util.net.TLSClientHelloExtractor
import java.nio.ByteBuffer

class TLSUtil {
    companion object {
        fun extractSNI(clientHello: ByteBuffer): String? {
            val extractor = TLSClientHelloExtractor(clientHello)
            return extractor.sniValue
        }
    }
}
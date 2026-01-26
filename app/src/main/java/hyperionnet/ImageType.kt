package hyperionnet

@Suppress("unused")
class ImageType private constructor() {
    companion object {
        const val NONE: Byte = 0
        const val RawImage: Byte = 1
        const val NV12Image: Byte = 2
        val names = arrayOf("NONE", "RawImage", "NV12Image")
        fun name(e: Int): String {
            return names[e]
        }
    }
}

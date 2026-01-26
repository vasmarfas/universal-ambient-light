package hyperionnet

@Suppress("unused")
class Command private constructor() {
    companion object {
        const val NONE: Byte = 0
        const val Color: Byte = 1
        const val Image: Byte = 2
        const val Clear: Byte = 3
        const val Register: Byte = 4
        val names = arrayOf("NONE", "Color", "Image", "Clear", "Register")
        fun name(e: Int): String {
            return names[e]
        }
    }
}

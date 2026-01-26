package com.vasmarfas.UniversalAmbientLight.common.network

import java.util.Objects

class ColorRgb(r: Int, g: Int, b: Int) : Cloneable {
    var red: Int = r and 0xFF
    var green: Int = g and 0xFF
    var blue: Int = b and 0xFF

    fun set(r: Int, g: Int, b: Int) {
        this.red = r and 0xFF
        this.green = g and 0xFF
        this.blue = b and 0xFF
    }

    fun set(other: ColorRgb?) {
        if (other != null) {
            this.red = other.red
            this.green = other.green
            this.blue = other.blue
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || javaClass != other.javaClass) return false
        val colorRgb = other as ColorRgb
        return red == colorRgb.red &&
                green == colorRgb.green &&
                blue == colorRgb.blue
    }

    override fun hashCode(): Int {
        return Objects.hash(red, green, blue)
    }

    public override fun clone(): ColorRgb {
        return try {
            super.clone() as ColorRgb
        } catch (e: CloneNotSupportedException) {
            ColorRgb(red, green, blue)
        }
    }
}

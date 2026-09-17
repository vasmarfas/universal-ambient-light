package com.vasmarfas.UniversalAmbientLight.ui.devices

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.LinearScale
import androidx.compose.material.icons.filled.Usb
import androidx.compose.ui.graphics.vector.ImageVector
import com.vasmarfas.UniversalAmbientLight.R
import com.vasmarfas.UniversalAmbientLight.common.network.OutputType

@StringRes
fun OutputType.titleRes(): Int = when (this) {
    OutputType.WLED -> R.string.output_wled
    OutputType.ADALIGHT -> R.string.output_adalight
    OutputType.HOME_ASSISTANT -> R.string.output_homeassistant
    OutputType.HYPERION -> R.string.output_hyperion
}

@StringRes
fun OutputType.summaryRes(): Int = when (this) {
    OutputType.WLED -> R.string.output_wled_summary
    OutputType.ADALIGHT -> R.string.output_adalight_summary
    OutputType.HOME_ASSISTANT -> R.string.output_homeassistant_summary
    OutputType.HYPERION -> R.string.output_hyperion_summary
}

@StringRes
fun OutputType.Group.titleRes(): Int = when (this) {
    OutputType.Group.STRIP -> R.string.devices_group_strip
    OutputType.Group.USB -> R.string.devices_group_usb
    OutputType.Group.LAMPS -> R.string.devices_group_lamps
    OutputType.Group.SERVER -> R.string.devices_group_server
}

val OutputType.Group.icon: ImageVector
    get() = when (this) {
        OutputType.Group.STRIP -> Icons.Default.LinearScale
        OutputType.Group.USB -> Icons.Default.Usb
        OutputType.Group.LAMPS -> Icons.Default.Lightbulb
        OutputType.Group.SERVER -> Icons.Default.Dns
    }

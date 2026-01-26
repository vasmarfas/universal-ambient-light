package com.vasmarfas.UniversalAmbientLight

import android.app.Application
import android.content.Context
import com.vasmarfas.UniversalAmbientLight.common.util.LocaleHelper

class AmbilightApplication : Application() {
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(LocaleHelper.onAttach(base))
    }
}

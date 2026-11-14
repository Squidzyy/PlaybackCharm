
package com.abdul.musicdisplay

import android.content.Context

object BleConnection {

    @Volatile
    private var instance: BleManager? = null

    fun get(context: Context): BleManager {
        return instance ?: synchronized(this) {
            instance ?: BleManager(context.applicationContext).also {
                instance = it
            }
        }
    }
}

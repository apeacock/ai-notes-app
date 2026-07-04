package com.ai.notes

import android.app.Application

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        provideAppLogger().init()
    }
}

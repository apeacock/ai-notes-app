package com.ai.notes

import timber.log.Timber

class DebugAppLogger : AppLogger {
    override fun init() {
        Timber.plant(Timber.DebugTree())
    }
}

fun provideAppLogger(): AppLogger = DebugAppLogger()

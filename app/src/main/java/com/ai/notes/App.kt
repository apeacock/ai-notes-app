package com.ai.notes

import android.app.Application
import androidx.appfunctions.service.AppFunctionConfiguration
import com.ai.notes.AppFunctions.NoteFunctions
import com.ai.notes.data.database.NoteDatabase
import com.ai.notes.data.database.repositories.NoteRepository

class App : Application(), AppFunctionConfiguration.Provider {

    private val repository by lazy {
        NoteRepository(NoteDatabase.getInstance(this).noteDao())
    }

    override fun onCreate() {
        super.onCreate()
        provideAppLogger().init()
    }

    override val appFunctionConfiguration: AppFunctionConfiguration
        get() = AppFunctionConfiguration.Builder()
            .addEnclosingClassFactory(NoteFunctions::class.java) { NoteFunctions(repository) }
            .build()
}

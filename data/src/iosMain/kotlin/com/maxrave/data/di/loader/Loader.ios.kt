package com.maxrave.data.di.loader

import com.maxrave.data.simpconnect.IosSimpConnectRepository
import com.maxrave.domain.repository.SimpConnectRepository
import org.koin.core.context.loadKoinModules
import org.koin.dsl.module

actual fun loadMediaService() {
}

actual fun loadLoginSyncModule() {
}

actual fun loadSimpConnectModule() {
    loadKoinModules(
        module {
            single<SimpConnectRepository>(createdAtStart = true) { IosSimpConnectRepository() }
        },
    )
}

package com.maxrave.data.di.loader

import com.simpmusic.media_jvm.di.loadDesktopPlayerModule
import com.maxrave.data.loginsync.LoginSyncHostRepositoryImpl
import com.maxrave.data.loginsync.LoginSyncStore
import com.maxrave.domain.repository.LoginSyncHostRepository
import org.koin.core.context.loadKoinModules
import org.koin.dsl.module

actual fun loadMediaService() {
    loadDesktopPlayerModule()
}

actual fun loadLoginSyncModule() {
    loadKoinModules(
        module {
            single { LoginSyncStore(get(), get(), get()) }
            single<LoginSyncHostRepository> { LoginSyncHostRepositoryImpl(get()) }
        },
    )
}

actual fun loadSimpConnectModule() {
    loadKoinModules(
        module {
            single<com.maxrave.domain.repository.SimpConnectRepository>(createdAtStart = true) {
                com.maxrave.data.simpconnect.SimpConnectRepositoryImpl(
                    mediaPlayerHandler = get(),
                    dataStoreManager = get(),
                    songRepository = get(),
                    scope = get(org.koin.core.qualifier.named(com.maxrave.common.Config.SERVICE_SCOPE)),
                ).also { it.start() }
            }
        },
    )
}

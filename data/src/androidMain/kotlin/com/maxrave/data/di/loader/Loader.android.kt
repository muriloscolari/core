package com.maxrave.data.di.loader

import com.maxrave.media3.di.loadMediaService
import com.maxrave.data.loginsync.LoginSyncSenderRepositoryImpl
import com.maxrave.data.loginsync.LoginSyncStore
import com.maxrave.domain.repository.LoginSyncSenderRepository
import org.koin.core.context.loadKoinModules
import org.simpmusic.loginsync.LoginSyncClient
import org.koin.dsl.module

actual fun loadMediaService() {
    loadMediaService()
}

actual fun loadLoginSyncModule() {
    loadKoinModules(
        module {
            single { LoginSyncStore(get(), get(), get()) }
            single<LoginSyncSenderRepository> { LoginSyncSenderRepositoryImpl(get(), LoginSyncClient()) }
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
                    scope = get(org.koin.core.qualifier.named(com.maxrave.common.Config.SERVICE_SCOPE)),
                ).also { it.start() }
            }
        },
    )
}

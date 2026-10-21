package org.simpmusic.simpconnect

interface SimpConnectAuthStore {
    fun isDeviceApproved(deviceId: String, authKey: String?): Boolean
    fun saveApprovedDevice(deviceId: String, authKey: String)
    fun getSavedAuthKey(deviceId: String): String?
}

class InMemorySimpConnectAuthStore : SimpConnectAuthStore {
    private val approvedKeys = java.util.concurrent.ConcurrentHashMap<String, String>()

    override fun isDeviceApproved(deviceId: String, authKey: String?): Boolean {
        if (authKey.isNullOrBlank()) return false
        val saved = approvedKeys[deviceId]
        return saved != null && saved == authKey
    }

    override fun saveApprovedDevice(deviceId: String, authKey: String) {
        approvedKeys[deviceId] = authKey
    }

    override fun getSavedAuthKey(deviceId: String): String? = approvedKeys[deviceId]
}

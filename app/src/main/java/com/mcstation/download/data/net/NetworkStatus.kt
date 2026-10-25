package com.mcstation.download.data.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build

/**
 * 当前联网方式。下载大体积安装包前据此判断是否需要提示流量消耗。
 *
 * [label] 会直接嵌进弹窗文案「您正在使用 xxx」，所以取用户能看懂的叫法。
 */
enum class NetworkKind(val label: String) {
    WIFI("Wifi"),
    CELLULAR("移动数据"),
    ETHERNET("以太网"),
    OTHER("非Wifi网络"),
    NONE("无网络"),
}

/**
 * 读取当前默认联网方式。
 *
 * 需要 ACCESS_NETWORK_STATE 权限（已在 AndroidManifest 声明）；拿不到权限或服务时按
 * [NetworkKind.OTHER] 处理（宁可多提示一次，也不要静默走掉几百 MB 流量）。
 */
fun currentNetworkKind(context: Context): NetworkKind {
    val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        ?: return NetworkKind.OTHER
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        val active = cm.activeNetwork ?: return NetworkKind.NONE
        val caps = cm.getNetworkCapabilities(active) ?: return NetworkKind.NONE
        when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> NetworkKind.WIFI
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> NetworkKind.CELLULAR
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> NetworkKind.ETHERNET
            // 挂 VPN 时 NetworkCapabilities 不一定带底层传输方式：此时若同时有 Wifi 就按 Wifi 算
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) && hasWifiNetwork(cm) -> NetworkKind.WIFI
            else -> NetworkKind.OTHER
        }
    } else {
        // Android 5.0/5.1（API 21/22）没有 NetworkCapabilities，用已废弃但可用的 activeNetworkInfo
        @Suppress("DEPRECATION")
        val info = cm.activeNetworkInfo ?: return NetworkKind.NONE
        @Suppress("DEPRECATION")
        when (info.type) {
            ConnectivityManager.TYPE_WIFI -> NetworkKind.WIFI
            ConnectivityManager.TYPE_MOBILE -> NetworkKind.CELLULAR
            ConnectivityManager.TYPE_ETHERNET -> NetworkKind.ETHERNET
            else -> NetworkKind.OTHER
        }
    }
}

/** 是否有可用的 Wifi 网络（VPN 场景判断底层网络用） */
private fun hasWifiNetwork(cm: ConnectivityManager): Boolean {
    @Suppress("DEPRECATION")
    return cm.allNetworks.any { network ->
        val caps = cm.getNetworkCapabilities(network) ?: return@any false
        caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
}

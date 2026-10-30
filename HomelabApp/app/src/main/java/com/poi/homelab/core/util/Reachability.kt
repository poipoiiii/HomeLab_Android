package com.poi.homelab.core.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.io.InterruptedIOException
import java.net.SocketException
import java.net.URI
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/** 脱机模式下「为什么连不上」的原因，不再只有通 / 不通。 */
enum class ReachabilityState {
    Online,
    NoNetwork,
    VpnOff,
    HostAsleep,
    ServiceDown,
    Failed,
}

data class Reachability(
    val state: ReachabilityState,
    /** 判定依据，直接显示在离线卡里。 */
    val detail: String? = null,
) {
    val online: Boolean get() = state == ReachabilityState.Online
}

object ReachabilityProbe {

    private val privateSuffixes = listOf(".ts.net", ".local", ".lan", ".home.arpa")

    /** 发一次 HEAD，把失败拆成：没网 / 没连 VPN / 主机睡了 / 服务没起。 */
    suspend fun probe(context: Context, targetUrl: String, base: OkHttpClient): Reachability =
        withContext(Dispatchers.IO) {
            val (hasNetwork, hasVpn) = networkFlags(context)
            if (!hasNetwork) {
                return@withContext Reachability(ReachabilityState.NoNetwork, "手机现在没联网，Wi-Fi 和流量都不通。")
            }
            val host = runCatching { URI(targetUrl.trim()).host }.getOrNull().orEmpty()
            // 内网 / Tailscale 地址没有 VPN 必然到不了，直接判定，不用干等 4 秒
            if (isPrivateHost(host) && !hasVpn) {
                return@withContext Reachability(ReachabilityState.VpnOff, "地址在内网，手机现在没有 VPN 连接。")
            }
            val client = base.newBuilder()
                .connectTimeout(3, TimeUnit.SECONDS)
                .readTimeout(3, TimeUnit.SECONDS)
                .callTimeout(4, TimeUnit.SECONDS)
                .build()
            try {
                client.newCall(Request.Builder().url(targetUrl).head().build()).execute().close()
                Reachability(ReachabilityState.Online, "HTTP 有响应。")
            } catch (error: UnknownHostException) {
                Reachability(ReachabilityState.VpnOff, "域名解析失败，通常是 VPN 没连上。")
            } catch (error: InterruptedIOException) {
                // callTimeout 与 read timeout 都落在这里
                if (hasVpn) {
                    Reachability(ReachabilityState.HostAsleep, "VPN 连着，但 4 秒没有响应。")
                } else {
                    Reachability(ReachabilityState.VpnOff, "连接超时，VPN 可能没连上。")
                }
            } catch (error: SocketException) {
                val message = error.message.orEmpty()
                when {
                    message.contains("refused", ignoreCase = true) ->
                        Reachability(ReachabilityState.ServiceDown, "端口拒绝连接，主机在线但服务没起。")
                    hasVpn -> Reachability(ReachabilityState.HostAsleep, "VPN 连着，但到主机的路由不通。")
                    else -> Reachability(ReachabilityState.VpnOff, "连不上主机，VPN 可能没连上。")
                }
            } catch (error: SSLException) {
                Reachability(ReachabilityState.Failed, "TLS 握手失败：${error.message ?: "证书或协议不匹配"}")
            } catch (error: Exception) {
                Reachability(ReachabilityState.Failed, error.message ?: error.javaClass.simpleName)
            }
        }

    /** 是否有可用网络、是否有 VPN（Tailscale 走 VpnService，会带 VPN transport）。 */
    @Suppress("DEPRECATION")
    fun networkFlags(context: Context): Pair<Boolean, Boolean> {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return true to false
        // VPN 不一定是默认网络（只走子网路由时 activeNetwork 还是 Wi-Fi），所以遍历全部网络
        val hasVpn = manager.allNetworks.any { network ->
            manager.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
        }
        val active = manager.getNetworkCapabilities(manager.activeNetwork ?: return false to hasVpn)
        val online = active?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        return online to hasVpn
    }

    private fun isPrivateHost(host: String): Boolean {
        val value = host.lowercase()
        if (value.isBlank()) return false
        if (privateSuffixes.any { value.endsWith(it) }) return true
        val numbers = value.split('.').mapNotNull { it.toIntOrNull() }
        if (numbers.size != 4) return false
        return when {
            numbers[0] == 100 && numbers[1] in 64..127 -> true // 100.64.0.0/10，Tailscale 常用段
            numbers[0] == 10 -> true
            numbers[0] == 192 && numbers[1] == 168 -> true
            numbers[0] == 172 && numbers[1] in 16..31 -> true
            else -> false
        }
    }
}

package com.soundboard

import org.json.JSONArray
import org.json.JSONObject

object RateLimitManager {

    private const val MAX_REQUESTS = 5
    private const val WINDOW_MS = 10 * 60 * 1000L // 10 minutes

    private var enabled = true
    private val requestLog = HashMap<String, MutableList<Long>>()
    private val listeners = mutableListOf<() -> Unit>()
    private val lock = Any()

    fun isEnabled(): Boolean = synchronized(lock) { enabled }

    fun setEnabled(value: Boolean) {
        synchronized(lock) { enabled = value }
    }

    data class RateLimitResult(
        val allowed: Boolean,
        val used: Int,
        val limit: Int,
        val remainingSeconds: Long
    )

    data class IpQuota(
        val ip: String,
        val used: Int,
        val limit: Int
    )

    fun checkAndRecord(ip: String): RateLimitResult {
        synchronized(lock) {
            if (!enabled) {
                return RateLimitResult(allowed = true, used = 0, limit = MAX_REQUESTS, remainingSeconds = 0)
            }

            val now = System.currentTimeMillis()
            val cutoff = now - WINDOW_MS

            val timestamps = requestLog.getOrPut(ip) { mutableListOf() }
            timestamps.removeAll { it < cutoff }

            if (timestamps.size >= MAX_REQUESTS) {
                val oldestInWindow = timestamps.min()
                val remainingMs = (oldestInWindow + WINDOW_MS) - now
                val remainingSec = (remainingMs / 1000).coerceAtLeast(1)
                return RateLimitResult(
                    allowed = false,
                    used = timestamps.size,
                    limit = MAX_REQUESTS,
                    remainingSeconds = remainingSec
                )
            }

            timestamps.add(now)
            notifyListeners()
            return RateLimitResult(
                allowed = true,
                used = timestamps.size,
                limit = MAX_REQUESTS,
                remainingSeconds = 0
            )
        }
    }

    fun getQuotas(): List<IpQuota> {
        synchronized(lock) {
            val now = System.currentTimeMillis()
            val cutoff = now - WINDOW_MS

            val result = mutableListOf<IpQuota>()
            val iterator = requestLog.iterator()
            while (iterator.hasNext()) {
                val (ip, timestamps) = iterator.next()
                timestamps.removeAll { it < cutoff }
                if (timestamps.isEmpty()) {
                    iterator.remove()
                } else {
                    result.add(IpQuota(ip = ip, used = timestamps.size, limit = MAX_REQUESTS))
                }
            }
            return result
        }
    }

    fun toJson(): String {
        val quotas = getQuotas()
        val array = JSONArray()
        for (quota in quotas) {
            val obj = JSONObject().apply {
                put("ip", quota.ip)
                put("used", quota.used)
                put("limit", quota.limit)
            }
            array.put(obj)
        }
        return JSONObject().apply {
            put("quotas", array)
            put("maxRequests", MAX_REQUESTS)
            put("windowMinutes", WINDOW_MS / 60000)
        }.toString()
    }

    fun addChangeListener(listener: () -> Unit) {
        synchronized(lock) {
            listeners.add(listener)
        }
    }

    fun removeChangeListener(listener: () -> Unit) {
        synchronized(lock) {
            listeners.remove(listener)
        }
    }

    private fun notifyListeners() {
        val listenersCopy: List<() -> Unit>
        synchronized(lock) {
            listenersCopy = listeners.toList()
        }
        listenersCopy.forEach { it() }
    }
}

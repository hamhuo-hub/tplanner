package com.hamhuo.tplanner

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

// 高德 Web API 逆地理编码：GPS (WGS-84) → 人类可读位置名。
// 三级回落：建筑名 → 兴趣点名 → 街道门牌 → 区县
// Web API 每日免费 5000 次（个人开发者），无需 Android SDK 依赖。
//
// 注意：高德 API 需要 GCJ-02 坐标。WakeDataLayerService
// 从 LocationManager 拿到的 GPS 定位可能是 WGS-84 也可能是 GCJ-02
// （取决于 ROM），本类不做转换——高德逆地理编码对坐标偏移有容忍度，
// 100-300 米的偏移一般不影响建筑物级别的位置识别。
object AmapGeocoder {

    private const val AMAP_REVERSE_GEO_URL = "https://restapi.amap.com/v3/geocode/regeo"

    private var cachedApiKey: String? = null

    fun setApiKey(key: String) { cachedApiKey = key }

    suspend fun reverseGeocode(
        lat: Double,
        lng: Double,
        apiKey: String = cachedApiKey ?: "",
    ): String = withContext(Dispatchers.IO) {
        try {
            val params = buildString {
                append("key=$apiKey")
                append("&location=$lng,$lat")      // 高德格式：经度,纬度
                append("&extensions=all")          // 返回建筑物、POI、道路信息
                append("&radius=200")              // 200m 内找最近 POI
                append("&output=JSON")
            }
            val url = "$AMAP_REVERSE_GEO_URL?$params"
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            try {
                if (conn.responseCode != 200) return@withContext fallback(lat, lng)
                val resp = BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8))
                    .readText()
                val json = JSONObject(resp)
                if (json.optInt("status") != 1) return@withContext fallback(lat, lng)

                val comp = json.getJSONObject("regeocode")
                    .getJSONObject("addressComponent")

                // 三级回落
                val building = comp.optJSONObject("building")
                if (building != null) {
                    val nameArr = building.optJSONArray("name")
                    if (nameArr != null && nameArr.length() > 0) {
                        return@withContext nameArr.getString(0)
                    }
                }

                // 最近 POI
                val pois = json.getJSONObject("regeocode")
                    .optJSONArray("pois")
                if (pois != null && pois.length() > 0) {
                    val poiName = pois.getJSONObject(0).optString("name", "")
                    if (poiName.isNotBlank() && poiName.length <= 20) {
                        return@withContext poiName
                    }
                }

                // 街道 + 门牌号
                val street = comp.optJSONObject("streetNumber")
                if (street != null) {
                    val streetName = street.optString("street", "")
                    val number = street.optString("number", "")
                    if (streetName.isNotBlank()) {
                        return@withContext if (number.isNotBlank()) "$streetName$number" else streetName
                    }
                }

                // 商圈
                val bizAreas = comp.optJSONArray("businessAreas")
                if (bizAreas != null && bizAreas.length() > 0) {
                    val bizName = bizAreas.getJSONObject(0).optString("name", "")
                    if (bizName.isNotBlank()) return@withContext bizName
                }

                // 区 + 街道
                val district = comp.optString("district", "")
                val township = comp.optString("township", "")
                when {
                    township.isNotBlank() -> township
                    district.isNotBlank() -> district
                    else -> fallback(lat, lng)
                }
            } finally {
                conn.disconnect()
            }
        } catch (_: Exception) {
            fallback(lat, lng)
        }
    }

    private fun fallback(lat: Double, lng: Double): String {
        // 最后的兜底：显示坐标本身
        return String.format("%.4f,%.4f", lat, lng)
    }
}

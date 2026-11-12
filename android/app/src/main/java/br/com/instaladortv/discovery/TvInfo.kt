package br.com.instaladortv.discovery

import org.json.JSONObject

/** [apiName] é o valor que o painel usa (`tizen`, `android_tv`, `webos`). */
enum class Platform(val label: String, val apiName: String) {
    TIZEN("Samsung Tizen", "tizen"),
    ANDROID_TV("Android TV", "android_tv"),
    WEBOS("LG webOS", "webos"),
}

data class TvInfo(
    val ip: String,
    val platform: Platform,
    val name: String = "",
    val model: String = "",
    val modelName: String = "",
    val year: Int? = null,
    val firmware: String = "",
    val duid: String = "",
    val developerMode: Boolean? = null,
    val developerIp: String = "",
    val sdbOpen: Boolean = false,
    val adbOpen: Boolean = false,
) {
    val factoryTizen: String get() = TIZEN_BY_YEAR[year] ?: ""

    companion object {
        /** Tizen de fábrica por ano de modelo; a versão real vem do `platform_version` do SDB. */
        val TIZEN_BY_YEAR = mapOf(
            2015 to "2.3", 2016 to "2.4", 2017 to "3.0", 2018 to "4.0", 2019 to "5.0", 2020 to "5.5",
            2021 to "6.0", 2022 to "6.5", 2023 to "7.0", 2024 to "8.0", 2025 to "9.0",
        )

        /** O campo `model` do /api/v2/ começa com o ano: `18_KANTM2_QTV` → 2018. */
        fun guessYear(model: String): Int? {
            val match = Regex("^(\\d{2})_").find(model) ?: return null
            return (2000 + match.groupValues[1].toInt()).takeIf { it in 2014..2040 }
        }

        /** Resposta de `GET http://IP:8001/api/v2/`. */
        fun fromSamsungApi(ip: String, json: JSONObject): TvInfo {
            val device = json.optJSONObject("device") ?: JSONObject()
            val model = device.optString("model")
            return TvInfo(
                ip = ip,
                platform = Platform.TIZEN,
                name = device.optString("name").ifEmpty { json.optString("name") },
                model = model,
                modelName = device.optString("modelName"),
                year = guessYear(model),
                firmware = device.optString("firmwareVersion"),
                duid = device.optString("duid"),
                developerMode = if (device.has("developerMode")) device.optString("developerMode") == "1" else null,
                developerIp = device.optString("developerIP"),
            )
        }
    }
}

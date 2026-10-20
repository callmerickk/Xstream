package com.lagradost.cloudstream3

object APIHolder {
    val unixTime: Long get() = System.currentTimeMillis() / 1000L
    val unixTimeMS: Long get() = System.currentTimeMillis()
    val allProviders: MutableList<MainAPI> = java.util.concurrent.CopyOnWriteArrayList()
    val apis: MutableList<MainAPI> get() = allProviders
    fun getApiFromNameNull(apiName: String?): MainAPI? = allProviders.firstOrNull { it.name == apiName }
    fun capitalize(str: String): String = str.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
}

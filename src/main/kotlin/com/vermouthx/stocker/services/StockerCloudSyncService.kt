package com.vermouthx.stocker.services

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.vermouthx.stocker.enums.StockerMarketType
import com.vermouthx.stocker.settings.StockerSetting
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Client service for syncing plugin data with the Stocker Cloud API.
 * Supports:
 * - Favorites: individual add/delete, batch add/delete, full overwrite sync
 * - Watchlist: full overwrite sync only (upload/download)
 */
@Service(Service.Level.APP)
class StockerCloudSyncService {

    private val log = Logger.getInstance(javaClass)
    private val gson = Gson()
    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build()

    companion object {
        val instance: StockerCloudSyncService
            get() = ApplicationManager.getApplication().getService(StockerCloudSyncService::class.java)

        private const val DEFAULT_BASE_URL = "http://localhost:8080"
        private const val API_KEY_HEADER = "X-API-Key"
    }

    var baseUrl: String = StockerSetting.instance.cloudSyncBaseUrl
    var apiKey: String = StockerSetting.instance.cloudSyncApiKey

    val isConfigured: Boolean
        get() = apiKey.isNotBlank()

    // ── Favorites API ───────────────────────────────────────────────────────────

    /** Download all favorites from cloud and overwrite local favorites list. */
    fun downloadFavorites(): Result<Int> = runCatching {
        val response = get("/api/v1/favorites")
        val body = parseBody<ApiResponse<FavoritesData>>(response)
        val items = body.data?.items ?: emptyList()

        val setting = StockerSetting.instance
        val newList = items.mapNotNull { item ->
            val market = StockerMarketType.fromPersistedId(item.marketId) ?: return@mapNotNull null
            setting.favoriteKey(market, item.code)
        }.toMutableList()
        setting.favoritesList = newList
        log.info("Downloaded ${newList.size} favorites from cloud")
        newList.size
    }

    /** Upload all local favorites to cloud (full overwrite). */
    fun uploadFavorites(): Result<Int> = runCatching {
        val setting = StockerSetting.instance
        val items = setting.favoritesList.mapNotNull { key ->
            setting.parseFavoriteKey(key)?.let { (market, code) ->
                FavoriteItem(marketId = market.persistedId, code = code)
            }
        }
        val body = SyncFavoritesRequest(items = items)
        put("/api/v1/favorites/sync", body)
        log.info("Uploaded ${items.size} favorites to cloud")
        items.size
    }

    /** Add a single favorite to cloud. */
    fun addFavoriteToCloud(market: StockerMarketType, code: String): Result<Boolean> = runCatching {
        val body = FavoriteItem(marketId = market.persistedId, code = code)
        val response = post("/api/v1/favorites/stocks", body)
        response.statusCode() == 201
    }

    /** Remove a single favorite from cloud. */
    fun removeFavoriteFromCloud(market: StockerMarketType, code: String): Result<Boolean> = runCatching {
        val body = FavoriteItem(marketId = market.persistedId, code = code)
        delete("/api/v1/favorites/stocks", body)
        true
    }

    /** Batch add favorites to cloud. */
    fun batchAddFavoritesToCloud(items: List<Pair<StockerMarketType, String>>): Result<Int> = runCatching {
        val body = BatchFavoritesRequest(items = items.map { (m, c) -> FavoriteItem(m.persistedId, c) })
        val response = post("/api/v1/favorites/stocks/batch", body)
        val result = parseBody<ApiResponse<Map<String, Int>>>(response)
        result.data?.get("added") ?: 0
    }

    /** Batch remove favorites from cloud. */
    fun batchRemoveFavoritesFromCloud(items: List<Pair<StockerMarketType, String>>): Result<Int> = runCatching {
        val body = BatchFavoritesRequest(items = items.map { (m, c) -> FavoriteItem(m.persistedId, c) })
        val response = delete("/api/v1/favorites/stocks/batch", body)
        val result = parseBody<ApiResponse<Map<String, Int>>>(response)
        result.data?.get("removed") ?: 0
    }

    // ── Watchlist API ───────────────────────────────────────────────────────────

    /** Download entire watchlist from cloud. Returns raw items for the bridge to consume. */
    fun downloadWatchlist(): Result<List<WatchlistItem>> = runCatching {
        val response = get("/api/v1/watchlist")
        val body = parseBody<ApiResponse<WatchlistData>>(response)
        body.data?.items ?: emptyList()
    }

    /** Upload entire watchlist to cloud (full overwrite). */
    fun uploadWatchlist(items: List<WatchlistItem>): Result<Int> = runCatching {
        val body = SyncWatchlistRequest(items = items)
        put("/api/v1/watchlist/sync", body)
        log.info("Uploaded ${items.size} watchlist entries to cloud")
        items.size
    }

    // ── HTTP Helpers ────────────────────────────────────────────────────────────

    private fun get(path: String): HttpResponse<String> {
        val request = HttpRequest.newBuilder()
            .uri(URI.create("$baseUrl$path"))
            .header(API_KEY_HEADER, apiKey)
            .GET()
            .build()
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString())
    }

    private fun post(path: String, body: Any): HttpResponse<String> {
        val request = HttpRequest.newBuilder()
            .uri(URI.create("$baseUrl$path"))
            .header(API_KEY_HEADER, apiKey)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(body)))
            .build()
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString())
    }

    private fun put(path: String, body: Any): HttpResponse<String> {
        val request = HttpRequest.newBuilder()
            .uri(URI.create("$baseUrl$path"))
            .header(API_KEY_HEADER, apiKey)
            .header("Content-Type", "application/json")
            .PUT(HttpRequest.BodyPublishers.ofString(gson.toJson(body)))
            .build()
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString())
    }

    private fun delete(path: String, body: Any): HttpResponse<String> {
        val request = HttpRequest.newBuilder()
            .uri(URI.create("$baseUrl$path"))
            .header(API_KEY_HEADER, apiKey)
            .header("Content-Type", "application/json")
            .method("DELETE", HttpRequest.BodyPublishers.ofString(gson.toJson(body)))
            .build()
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString())
    }

    private inline fun <reified T> parseBody(response: HttpResponse<String>): T {
        if (response.statusCode() !in 200..299) {
            throw CloudSyncException("API error: ${response.statusCode()} - ${response.body()}")
        }
        return gson.fromJson(response.body(), object : TypeToken<T>() {}.type)
    }

    // ── DTOs (mirror of cloud API) ──────────────────────────────────────────────

    data class FavoriteItem(val marketId: String, val code: String)
    data class SyncFavoritesRequest(val items: List<FavoriteItem>)
    data class BatchFavoritesRequest(val items: List<FavoriteItem>)
    data class FavoritesData(val items: List<FavoriteItem>, val total: Int)

    data class WatchlistItem(
        val symbol: String,
        val normalizedKey: String,
        val name: String? = null,
        val sector: String? = null,
        val thesis: String? = null,
        val trigger: String? = null,
        val targetZoneLow: Double? = null,
        val targetZoneHigh: Double? = null,
        val invalidation: String? = null,
        val refPrice: Double? = null,
    )
    data class SyncWatchlistRequest(val items: List<WatchlistItem>)
    data class WatchlistData(val items: List<WatchlistItem>, val total: Int)

    data class ApiResponse<T>(val success: Boolean, val data: T?, val message: String?)

    class CloudSyncException(message: String) : RuntimeException(message)
}

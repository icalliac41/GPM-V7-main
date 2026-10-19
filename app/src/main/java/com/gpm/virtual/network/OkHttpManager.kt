package com.gpm.virtual.network

import android.content.Context
import android.util.Log
import com.gpm.virtual.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Cache
import okhttp3.CacheControl
import okhttp3.Call
import okhttp3.Callback
import okhttp3.ConnectionPool
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.logging.HttpLoggingInterceptor
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Centralized OkHttp manager providing:
 * 1. HTTP/HTTPS Network Requests with timeout management
 * 2. Connection Pooling (reusing socket connections for lower latency & resource saving)
 * 3. HTTP/2 Multiplexing Support (concurrent requests on single connection)
 * 4. Disk Caching & Automatic GZIP Compression/Decompression
 * 5. Custom Interceptors (Retry with backoff, User-Agent, CacheControl, Logging)
 */
object OkHttpManager {

    private const val TAG = "OkHttpManager"
    private const val CACHE_SIZE_BYTES = 20L * 1024L * 1024L // 20 MB cache
    private const val DEFAULT_USER_AGENT = "GPM-VirtualMaps/1.0 (Android; OkHttp)"

    private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var okHttpClient: OkHttpClient? = null

    val connectionPool = ConnectionPool(
        maxIdleConnections = 10,
        keepAliveDuration = 5L,
        timeUnit = TimeUnit.MINUTES
    )

    fun init(context: Context) {
        if (appContext == null) {
            appContext = context.applicationContext
            buildClient()
        }
    }

    val client: OkHttpClient
        get() {
            return okHttpClient ?: synchronized(this) {
                okHttpClient ?: buildClient().also { okHttpClient = it }
            }
        }

    private fun buildClient(): OkHttpClient {
        val builder = OkHttpClient.Builder()
            // ── 1. Connection & Timeouts ────────────────────────
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS)

            // ── 2. Connection Pooling (Reuse connections) ───────
            .connectionPool(connectionPool)

            // ── 3. HTTP/2 & HTTP/1.1 Support ────────────────────
            .protocols(listOf(Protocol.HTTP_2, Protocol.HTTP_1_1))

            // ── 4. Automatic Retry on Connection Failure ────────
            .retryOnConnectionFailure(true)

        // ── 5. Disk Cache ───────────────────────────────────────
        val ctx = appContext
        if (ctx != null) {
            try {
                val cacheDir = File(ctx.cacheDir, "http_cache")
                if (!cacheDir.exists()) cacheDir.mkdirs()
                builder.cache(Cache(cacheDir, CACHE_SIZE_BYTES))
            } catch (t: Throwable) {
                Log.w(TAG, "Failed to initialize HTTP cache directory: ${t.message}")
            }
        }

        // ── 6. Interceptors ─────────────────────────────────────
        // a. Retry Interceptor (Exponential Backoff for transient failures)
        builder.addInterceptor(RetryInterceptor(maxRetries = 2))

        // b. Headers & User-Agent Interceptor
        builder.addInterceptor(HeaderInterceptor())

        // c. Cache-Control Optimization Interceptor
        builder.addNetworkInterceptor(CacheControlInterceptor())

        // d. Logging Interceptor (Only active in debug builds)
        if (BuildConfig.DEBUG) {
            val logging = HttpLoggingInterceptor { message ->
                Log.d(TAG, message)
            }.apply {
                level = HttpLoggingInterceptor.Level.BASIC
            }
            builder.addInterceptor(logging)
        }

        val built = builder.build()
        okHttpClient = built
        Log.i(TAG, "✅ OkHttpClient initialized with HTTP/2, ConnectionPool(10, 5m), DiskCache(20MB), and Interceptors")
        return built
    }

    /**
     * Executes an asynchronous HTTP GET request returning the response body as String.
     */
    suspend fun get(url: String, headers: Map<String, String> = emptyMap(), cacheControl: CacheControl? = null): String {
        return withContext(Dispatchers.IO) {
            val requestBuilder = Request.Builder().url(url)
            headers.forEach { (k, v) -> requestBuilder.header(k, v) }
            if (cacheControl != null) requestBuilder.cacheControl(cacheControl)

            val request = requestBuilder.build()
            executeRequest(request)
        }
    }

    /**
     * Executes an asynchronous HTTP POST JSON request returning the response body as String.
     */
    suspend fun postJson(url: String, json: String, headers: Map<String, String> = emptyMap()): String {
        return withContext(Dispatchers.IO) {
            val body = json.toRequestBody(JSON_MEDIA_TYPE)
            val requestBuilder = Request.Builder().url(url).post(body)
            headers.forEach { (k, v) -> requestBuilder.header(k, v) }

            val request = requestBuilder.build()
            executeRequest(request)
        }
    }

    private suspend fun executeRequest(request: Request): String {
        return suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request)
            continuation.invokeOnCancellation {
                call.cancel()
            }

            call.enqueue(object : Callback {
                override fun onResponse(call: Call, response: Response) {
                    try {
                        if (!response.isSuccessful) {
                            continuation.resumeWithException(
                                IOException("Unexpected HTTP code ${response.code}: ${response.message}")
                            )
                            return
                        }
                        val bodyString = response.body?.string().orEmpty()
                        continuation.resume(bodyString)
                    } catch (t: Throwable) {
                        continuation.resumeWithException(t)
                    } finally {
                        response.close()
                    }
                }

                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isCancelled) return
                    continuation.resumeWithException(e)
                }
            })
        }
    }

    /**
     * Clears all cached HTTP responses on disk.
     */
    fun clearCache() {
        try {
            client.cache?.evictAll()
            Log.i(TAG, "HTTP cache evicted successfully")
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to evict cache: ${t.message}")
        }
    }

    /**
     * Returns the current disk cache size in bytes.
     */
    fun getCacheSize(): Long {
        return try {
            client.cache?.size() ?: 0L
        } catch (_: Throwable) { 0L }
    }

    /**
     * Returns stats for connection pool: (idleConnections, totalConnections)
     */
    fun getConnectionPoolStats(): Pair<Int, Int> {
        val pool = client.connectionPool
        return pool.idleConnectionCount() to pool.connectionCount()
    }

    // ── INTERCEPTORS ──────────────────────────────────────────

    @Volatile
    private var currentAuthToken: String? = null

    /**
     * Sets or clears the dynamic authorization token added to requests via Auth Interceptor.
     */
    fun setAuthToken(token: String?) {
        currentAuthToken = token
        Log.d(TAG, "Authorization token updated: ${if (token.isNullOrBlank()) "CLEARED" else "SET"}")
    }

    fun getAuthToken(): String? = currentAuthToken

    /**
     * Executes an asynchronous HTTP POST Form (application/x-www-form-urlencoded) request.
     */
    suspend fun postForm(url: String, formParams: Map<String, String>, headers: Map<String, String> = emptyMap()): String {
        return withContext(Dispatchers.IO) {
            val formBuilder = okhttp3.FormBody.Builder()
            formParams.forEach { (k, v) -> formBuilder.add(k, v) }
            val requestBuilder = Request.Builder().url(url).post(formBuilder.build())
            headers.forEach { (k, v) -> requestBuilder.header(k, v) }

            val request = requestBuilder.build()
            executeRequest(request)
        }
    }

    /**
     * Interceptor to add default headers, standard User-Agent, Authorization token, and Accept headers.
     */
    private class HeaderInterceptor : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val original = chain.request()
            val builder = original.newBuilder()

            if (original.header("User-Agent") == null) {
                builder.header("User-Agent", DEFAULT_USER_AGENT)
            }
            if (original.header("Accept") == null) {
                builder.header("Accept", "application/json, text/plain, */*")
            }
            // Dynamic Auth Token injection
            currentAuthToken?.takeIf { it.isNotBlank() }?.let { token ->
                if (original.header("Authorization") == null) {
                    builder.header("Authorization", if (token.startsWith("Bearer ")) token else "Bearer $token")
                }
            }
            // OkHttp handles Accept-Encoding: gzip automatically by default
            return chain.proceed(builder.build())
        }
    }

    /**
     * Network interceptor to ensure responses are cached appropriately when supported.
     */
    private class CacheControlInterceptor : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            val response = chain.proceed(request)

            if (request.method == "GET" && response.isSuccessful) {
                val existingCache = response.header("Cache-Control")
                if (existingCache == null || existingCache.contains("no-store") || existingCache.contains("no-cache")) {
                    // Cache for 1 hour by default to save data & battery
                    return response.newBuilder()
                        .header("Cache-Control", "public, max-age=3600")
                        .removeHeader("Pragma")
                        .build()
                }
            }
            return response
        }
    }

    /**
     * Retry interceptor that catches IOExceptions and retries up to [maxRetries] times with exponential backoff.
     */
    private class RetryInterceptor(private val maxRetries: Int) : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            var response: Response? = null
            var exception: IOException? = null
            var retryCount = 0

            while (retryCount <= maxRetries) {
                try {
                    response = chain.proceed(request)
                    if (response.isSuccessful || retryCount == maxRetries) {
                        return response
                    }
                    // For server 5xx errors, retry
                    if (response.code in 500..599) {
                        response.close()
                        throw IOException("Server returned HTTP ${response.code}")
                    }
                    return response
                } catch (e: IOException) {
                    exception = e
                    retryCount++
                    if (retryCount > maxRetries) break

                    val backoffMs = (300L * (1 shl (retryCount - 1))).coerceAtMost(2000L)
                    try {
                        Thread.sleep(backoffMs)
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                        throw e
                    }
                    Log.d(TAG, "Retrying request (${request.url}) attempt #$retryCount after ${backoffMs}ms")
                }
            }
            throw exception ?: IOException("Request failed after $maxRetries retries")
        }
    }
}

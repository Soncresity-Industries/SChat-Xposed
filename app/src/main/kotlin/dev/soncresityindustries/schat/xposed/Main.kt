package dev.soncresityindustries.schat.xposed

import android.app.Activity
import android.content.res.AssetManager
import android.content.res.Resources
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.callbacks.XC_LoadPackage
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.RedirectResponseException
import io.ktor.client.plugins.UserAgent
import io.ktor.client.request.get
import io.ktor.client.request.headers
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File

@Serializable
data class CustomLoadUrl(
    val enabled: Boolean,
    val url: String
)

@Serializable
data class LoaderConfig(
    val customLoadUrl: CustomLoadUrl
)

class Main : IXposedHookLoadPackage {
    companion object {
        init {
            Log.e("SChat", ">>> SChat-Xposed Module Initialized <<<")
        }
    }

    private val schatModules: Array<SChatModule> = arrayOf(
        ThemeModule(),
        SysColorsModule(),
        FontsModule(),
        LogBoxModule()
    )

    fun buildLoaderJsonString(): String {
        val obj = buildJsonObject {
            put("loaderName", "SChat-Xposed")
            put("loaderVersion", BuildConfig.VERSION_NAME)

            for (module in schatModules) {
                module.buildJson(this)
            }
        }

        return Json.encodeToString(obj)
    }

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        Log.e("SChat", ">>> handleLoadPackage called for ${lpparam.packageName} <<<")

        try {
            val reactActivity = runCatching {
                lpparam.classLoader.loadClass("com.discord.react_activities.ReactActivity")
            }.getOrElse {
                Log.e("SChat", "ReactActivity not found in ${lpparam.packageName}")
                return
            }

            var activity: Activity? = null
            val onActivityCreateCallback = mutableSetOf<(activity: Activity) -> Unit>()

            XposedBridge.hookMethod(
                reactActivity.getDeclaredMethod("onCreate", Bundle::class.java),
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        activity = param.thisObject as Activity
                        onActivityCreateCallback.forEach { cb -> cb(activity) }
                        onActivityCreateCallback.clear()
                    }
                })

            init(lpparam) { cb ->
                if (activity != null) cb(activity)
                else onActivityCreateCallback.add(cb)
            }
        } catch (e: Throwable) {
            Log.e("SChat", "Critical error in handleLoadPackage", e)
        }
    }

    private fun init(
        param: XC_LoadPackage.LoadPackageParam,
        onActivityCreate: ((activity: Activity) -> Unit) -> Unit
    ) = with(param) {
        try {
            val contextClass = try {
                classLoader.loadClass("com.facebook.react.bridge.CatalystInstanceImpl")
            } catch (e: ClassNotFoundException) {
                Log.e("SChat", "CatalystInstanceImpl not found, trying BridgelessReactContext...")
                try {
                    classLoader.loadClass("com.facebook.react.bridge.BridgelessReactContext")
                } catch (e2: ClassNotFoundException) {
                    Log.e("SChat", "No suitable React context class found. Bundle hooks will be disabled.")
                    null
                }
            }

            for (module in schatModules) module.onInit(param)

            if (contextClass == null) {
                Log.e("SChat", "Skipping bundle hooks due to missing context class.")
            } else {
                applyBundleHooks(contextClass, param, onActivityCreate)
            }
        } catch (e: Throwable) {
            Log.e("SChat", "Error during init", e)
        }
    }

    private fun applyBundleHooks(
        contextClass: Class<*>,
        param: XC_LoadPackage.LoadPackageParam,
        onActivityCreate: ((activity: Activity) -> Unit) -> Unit
    ) = with(param) {
        try {
            val loadScriptFromAssets = contextClass.getDeclaredMethod(
                "loadScriptFromAssets",
                AssetManager::class.java,
                String::class.java,
                Boolean::class.javaPrimitiveType
            ).apply { isAccessible = true }

            val loadScriptFromFile = contextClass.getDeclaredMethod(
                "loadScriptFromFile",
                String::class.java,
                String::class.java,
                Boolean::class.javaPrimitiveType
            ).apply { isAccessible = true }

            val setGlobalVariable = contextClass.getDeclaredMethod(
                "setGlobalVariable",
                String::class.java,
                String::class.java
            ).apply { isAccessible = true }

            val cacheDir = File(appInfo.dataDir, "cache/schat").apply { mkdirs() }
            val filesDir = File(appInfo.dataDir, "files/schat").apply { mkdirs() }
            val preloadsDir = File(filesDir, "preloads").apply { mkdirs() }
            val bundle = File(cacheDir, "bundle.js")
            val etag = File(cacheDir, "etag.txt")
            val configFile = File(filesDir, "loader.json")

            val config = try {
                if (!configFile.exists()) throw Exception()
                val json = Json { ignoreUnknownKeys = true }
                json.decodeFromString(configFile.readText())
            } catch (_: Exception) {
                LoaderConfig(customLoadUrl = CustomLoadUrl(enabled = false, url = ""))
            }

            val scope = MainScope()
            scope.async(Dispatchers.IO) {
                try {
                    val client = HttpClient(CIO) {
                        expectSuccess = true
                        install(HttpTimeout) {
                            requestTimeoutMillis = if (bundle.exists()) 5000 else 10000
                        }
                        install(UserAgent) { agent = "SChat-Xposed" }
                    }

                    val url = if (config.customLoadUrl.enabled) config.customLoadUrl.url
                               else "https://raw.githubusercontent.com/Soncresity-Industries/SChat-builds/main/schat.min.js"

                    Log.e("SChat", "Fetching JS bundle from $url")

                    val response: HttpResponse = client.get(url) {
                        headers {
                            if (etag.exists() && bundle.exists()) {
                                append(HttpHeaders.IfNoneMatch, etag.readText())
                            }
                        }
                    }

                    bundle.writeBytes(response.body())
                    if (response.headers["Etag"] != null) {
                        etag.writeText(response.headers["Etag"]!!)
                    } else if (etag.exists()) {
                        etag.delete()
                    }
                } catch (e: RedirectResponseException) {
                    if (e.response.status != HttpStatusCode.NotModified) throw e
                    Log.e("SChat", "Server responded with status code 304 - no changes to file")
                } catch (e: Throwable) {
                    onActivityCreate { activity ->
                        activity.runOnUiThread {
                            Toast.makeText(activity.applicationContext, "Failed to fetch JS bundle!", Toast.LENGTH_SHORT).show()
                        }
                    }
                    Log.e("SChat", "Failed to download bundle", e)
                }
            }

            val patch = object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    Log.e("SChat", "Before loading scripts")
                    XposedBridge.invokeOriginalMethod(setGlobalVariable, param.thisObject, arrayOf("__SCHAT_LOADER__", buildLoaderJsonString()))

                    preloadsDir.walk().filter { it.isFile && it.extension == "js" }.forEach { file ->
                        Log.e("SChat", "Loading preload: ${file.name}")
                        XposedBridge.invokeOriginalMethod(loadScriptFromFile, param.thisObject, arrayOf(file.absolutePath, file.absolutePath, param.args[2]))
                    }

                    Log.e("SChat", "Loading main bundle: ${bundle.absolutePath}")
                    XposedBridge.invokeOriginalMethod(loadScriptFromFile, param.thisObject, arrayOf(bundle.absolutePath, bundle.absolutePath, param.args[2]))
                    Log.e("SChat", "Finished loading scripts")
                }
            }

            XposedBridge.hookMethod(loadScriptFromAssets, patch)
            XposedBridge.hookMethod(loadScriptFromFile, patch)
            Log.e("SChat", "Bundle hooks applied successfully using ${contextClass.simpleName}")
        } catch (e: Throwable) {
            Log.e("SChat", "Failed to apply bundle hooks", e)
        }
    }
}

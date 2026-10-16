package fr.zapette.tv

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import fr.zapette.tv.data.Store
import fr.zapette.tv.engine.AddonEngine

class ZapetteApplication : Application(), ImageLoaderFactory {

    lateinit var engine: AddonEngine
        private set
    lateinit var store: Store
        private set

    override fun onCreate() {
        super.onCreate()
        engine = AddonEngine(this)
        store = Store(this)
    }

    /** Channel logos and programme pictures are reused a lot: cache them generously. */
    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .crossfade(180)
        .memoryCache { MemoryCache.Builder(this).maxSizePercent(0.20).build() }
        .diskCache {
            DiskCache.Builder().directory(cacheDir.resolve("images")).maxSizeBytes(150L * 1024 * 1024).build()
        }
        .respectCacheHeaders(false)
        .build()
}

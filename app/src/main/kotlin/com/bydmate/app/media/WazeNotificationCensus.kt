package com.bydmate.app.media

import android.app.Notification
import android.graphics.drawable.Icon

/**
 * Privacy-safe shape census of the navigation notifications this listener actually receives.
 *
 * The live windshield HUD keeps distance and street but never a maneuver, and no runtime evidence
 * exists for whether Waze posts a navigation notification at all on this DiLink build, whether it
 * carries custom [android.widget.RemoteViews], or whether the arrow lives in the small/large icon.
 * Answering that from a single normal route requires shape metadata, not content: counters, the
 * framework category/channel identifiers, extras key names with their value type names, and icon
 * type/resource identifiers. Notification text, streets, addresses and pixels never enter this
 * object, diagnostics or persistent storage.
 */
object WazeNotificationCensus {
    private const val MAX_EXTRA_ENTRIES = 48
    private const val MAX_KEY_CHARS = 64
    private const val MAX_RESOURCE_NAME_CHARS = 96

    /** Shape metadata of a single observed notification. */
    data class Shape(
        val postAtMs: Long = 0L,
        val category: String? = null,
        val channelId: String? = null,
        /** "key:ValueTypeSimpleName" pairs; values themselves are never read into the census. */
        val extras: List<String> = emptyList(),
        val contentView: Boolean = false,
        val bigContentView: Boolean = false,
        val headsUpContentView: Boolean = false,
        val smallIconType: Int? = null,
        val smallIconResource: String? = null,
        val largeIconType: Int? = null,
        val largeIconResource: String? = null,
    )

    data class Snapshot(
        val posted: Int = 0,
        val accepted: Int = 0,
        val rejected: Int = 0,
        val lastPostAtMs: Long? = null,
        val lastAccepted: Boolean? = null,
        /**
         * Shapes are retained per verdict rather than in one "last" slot. Waze posts community
         * alerts and engagement cards during the same route, and any of them arriving after the
         * navigation notification would otherwise overwrite the only evidence of the shape the
         * HUD actually depends on.
         */
        val lastAcceptedShape: Shape? = null,
        val lastRejectedShape: Shape? = null,
    )

    @Volatile private var latest = Snapshot()

    fun snapshot(): Snapshot = latest

    /** Test hook; the listener never clears its own history during a route. */
    fun reset() {
        latest = Snapshot()
    }

    @Synchronized
    fun record(
        notification: Notification,
        accepted: Boolean,
        resolveName: (Int) -> String?,
        nowMs: Long = System.currentTimeMillis(),
    ) {
        val previous = latest
        val shape = shapeOf(notification, resolveName, nowMs)
        latest = previous.copy(
            posted = previous.posted + 1,
            accepted = previous.accepted + if (accepted) 1 else 0,
            rejected = previous.rejected + if (accepted) 0 else 1,
            lastPostAtMs = nowMs,
            lastAccepted = accepted,
            lastAcceptedShape = if (accepted) shape else previous.lastAcceptedShape,
            lastRejectedShape = if (accepted) previous.lastRejectedShape else shape,
        )
    }

    private fun shapeOf(
        notification: Notification,
        resolveName: (Int) -> String?,
        nowMs: Long,
    ): Shape = Shape(
        postAtMs = nowMs,
        category = notification.category,
        channelId = runCatching { notification.channelId }.getOrNull(),
        extras = extrasShape(notification),
        contentView = runCatching { notification.contentView != null }.getOrDefault(false),
        bigContentView = runCatching { notification.bigContentView != null }.getOrDefault(false),
        headsUpContentView = runCatching { notification.headsUpContentView != null }
            .getOrDefault(false),
        smallIconType = iconType(runCatching { notification.smallIcon }.getOrNull()),
        smallIconResource = iconResource(
            runCatching { notification.smallIcon }.getOrNull(),
            resolveName,
        ),
        largeIconType = iconType(runCatching { notification.getLargeIcon() }.getOrNull()),
        largeIconResource = iconResource(
            runCatching { notification.getLargeIcon() }.getOrNull(),
            resolveName,
        ),
    )

    /** Key names plus value type names only. A vendor key name is not user content. */
    private fun extrasShape(notification: Notification): List<String> {
        val extras = notification.extras ?: return emptyList()
        return runCatching {
            extras.keySet().sorted().take(MAX_EXTRA_ENTRIES).map { key ->
                val type = runCatching { extras.get(key) }.getOrNull()
                    ?.javaClass?.simpleName
                    ?.takeIf { it.isNotEmpty() }
                    ?: "null"
                "${key.take(MAX_KEY_CHARS)}:$type"
            }
        }.getOrDefault(emptyList())
    }

    private fun iconType(icon: Icon?): Int? =
        icon?.let { runCatching { it.type }.getOrNull() }

    /** Only a resource entry name is resolved; bitmap, URI and data icons expose nothing here. */
    private fun iconResource(icon: Icon?, resolveName: (Int) -> String?): String? {
        val resolved = icon ?: return null
        val resourceId = runCatching {
            resolved.takeIf { it.type == Icon.TYPE_RESOURCE }?.resId
        }.getOrNull() ?: return null
        if (resourceId == 0) return null
        return runCatching { resolveName(resourceId) }.getOrNull()
            ?.take(MAX_RESOURCE_NAME_CHARS)
    }
}

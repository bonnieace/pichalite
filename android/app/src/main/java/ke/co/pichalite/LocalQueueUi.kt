package ke.co.pichalite

/** Prepared off the UI thread; composition only reads this immutable snapshot. */
internal data class LocalPhotoUi(val status: String?, val retry: Boolean)
internal data class LocalQueueUi(
    val photos: Map<String, LocalPhotoUi> = emptyMap(),
    val waiting: Int = 0,
    val attention: Int = 0,
    val replacing: Int = 0,
    val replaced: Int = 0,
    val retained: Int = 0,
) {
    companion object {
        fun read(photos: List<Photo>, files: LocalPhotoFiles): LocalQueueUi {
            check(android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) { "Prepare local photo metadata off the UI thread" }
            val result = HashMap<String, LocalPhotoUi>(photos.size)
            var waiting = 0; var attention = 0; var replacing = 0; var replaced = 0; var retained = 0
            for (photo in photos) {
                val local = files.hasLocalSource(photo)
                val deleted = local && files.wasDeleted(photo)
                val stage = files.replacementState(photo)
                val retry = local && !deleted && photo.status == "ready" && stage in setOf("error", "kept")
                if (!deleted && stage == "awaiting") waiting++
                if (retry) attention++
                if (photo.status == "ready" && local && !files.cacheReleased(photo) && !retry && stage != "not_smaller" && (!deleted || files.hasExport(photo))) replacing++
                if (deleted) replaced++
                if (stage == "not_smaller" || stage == "kept") retained++
                result[photo.id] = LocalPhotoUi(if (local) files.replacementStatus(photo) else null, retry)
            }
            return LocalQueueUi(result, waiting, attention, replacing, replaced, retained)
        }
    }
}

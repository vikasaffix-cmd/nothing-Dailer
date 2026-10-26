package com.example.allinonedailer.data

import android.content.Context
import android.database.ContentObserver
import android.provider.ContactsContract
import android.util.Log
import android.os.SystemClock
import java.util.concurrent.atomic.AtomicInteger

data class ContactItem(
    val id: String,
    val name: String,
    val phoneNumber: String,
    val photoUri: String? = null,
    val starred: Boolean = false
)

class ContactsRepository(private val context: Context) {

    /**
     * One in-memory copy of the contacts for every screen (keypad, Contacts,
     * speed dial, widget, Recents' caller lookups). A ContentObserver drops it
     * when anything in the contacts provider changes, so the next load re-reads.
     */
    companion object {
        private val lock = Any()

        @Volatile
        private var allNumbers: List<ContactItem>? = null
        @Volatile
        private var onePerContactCache: List<ContactItem>? = null
        @Volatile
        private var byKey: Map<String, ContactItem>? = null
        @Volatile
        private var observing = false

        /** The cached list, or null if it hasn't been loaded (or contacts changed since). */
        fun cached(onePerContact: Boolean = true): List<ContactItem>? =
            if (onePerContact) onePerContactCache else allNumbers

        /**
         * Saved contacts by [PhoneNumbers.key], or null when the cache is empty.
         * Lets the call log tell "not a contact" without a query per number.
         */
        fun cachedByKey(): Map<String, ContactItem>? {
            byKey?.let { return it }
            val all = allNumbers ?: return null
            val map = HashMap<String, ContactItem>(all.size * 2)
            for (c in all) {
                val k = PhoneNumbers.key(c.phoneNumber)
                if (k.isNotEmpty()) map.putIfAbsent(k, c)
            }
            byKey = map
            return map
        }

        /** Bumped on every change, so a load that overlapped one isn't kept. */
        private val generation = AtomicInteger()

        private fun invalidate() {
            generation.incrementAndGet()
            allNumbers = null
            onePerContactCache = null
            byKey = null
        }

        private fun observe(context: Context) {
            if (observing) return
            try {
                context.applicationContext.contentResolver.registerContentObserver(
                    ContactsContract.AUTHORITY_URI,
                    true,
                    object : ContentObserver(null) {
                        override fun onChange(selfChange: Boolean) = invalidate()
                    }
                )
                observing = true
            } catch (e: Exception) {
                // No permission yet: don't cache, so the next load queries again.
            }
        }
    }

    /**
     * Device contacts sorted by name. With [onePerContact] each contact appears
     * once (its first number); otherwise every number is its own entry, which
     * the keypad uses for number matching. Served from the shared cache when
     * nothing has changed. Call off the main thread.
     */
    fun loadContacts(onePerContact: Boolean = true): List<ContactItem> {
        cached(onePerContact)?.let { return it }
        synchronized(lock) {
            cached(onePerContact)?.let { return it }
            val startedAt = SystemClock.elapsedRealtime()
            // Register before reading so a change during the query isn't missed.
            observe(context)
            val gen = generation.get()
            val list = query() ?: return emptyList()
            if (observing && gen == generation.get()) {
                allNumbers = list
                onePerContactCache = list.distinctBy { it.id }
            }
            Log.d("ContactsRepository", "Loaded ${list.size} numbers in ${SystemClock.elapsedRealtime() - startedAt} ms")
            return if (onePerContact) list.distinctBy { it.id } else list
        }
    }

    /** Null when the query failed (e.g. no permission), so nothing is cached. */
    private fun query(): List<ContactItem>? {
        val list = mutableListOf<ContactItem>()
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER,
            ContactsContract.CommonDataKinds.Phone.PHOTO_THUMBNAIL_URI,
            ContactsContract.CommonDataKinds.Phone.STARRED
        )
        try {
            val cursor = context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                projection,
                null,
                null,
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} COLLATE NOCASE ASC"
            ) ?: return null
            cursor.use {
                val idIdx = cursor.getColumnIndex(projection[0])
                val nameIdx = cursor.getColumnIndex(projection[1])
                val numIdx = cursor.getColumnIndex(projection[2])
                val photoIdx = cursor.getColumnIndex(projection[3])
                val starIdx = cursor.getColumnIndex(projection[4])
                while (cursor.moveToNext()) {
                    val number = cursor.getString(numIdx) ?: continue
                    if (number.isBlank()) continue
                    list += ContactItem(
                        id = cursor.getString(idIdx),
                        name = cursor.getString(nameIdx) ?: number,
                        phoneNumber = number,
                        photoUri = if (photoIdx != -1) cursor.getString(photoIdx) else null,
                        starred = starIdx != -1 && cursor.getInt(starIdx) == 1
                    )
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        }
        return list
    }
}

package com.example.allinonedailer.data

import android.content.Context
import android.provider.ContactsContract

data class ContactItem(
    val id: String,
    val name: String,
    val phoneNumber: String,
    val photoUri: String? = null,
    val starred: Boolean = false
)

class ContactsRepository(private val context: Context) {

    /**
     * Device contacts sorted by name. With [onePerContact] each contact appears
     * once (its first number); otherwise every number is its own entry, which
     * the keypad uses for number matching. Call off the main thread.
     */
    fun loadContacts(onePerContact: Boolean = true): List<ContactItem> {
        val list = mutableListOf<ContactItem>()
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER,
            ContactsContract.CommonDataKinds.Phone.PHOTO_THUMBNAIL_URI,
            ContactsContract.CommonDataKinds.Phone.STARRED
        )
        try {
            context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                projection,
                null,
                null,
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} COLLATE NOCASE ASC"
            )?.use { cursor ->
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
        }
        return if (onePerContact) list.distinctBy { it.id } else list
    }
}

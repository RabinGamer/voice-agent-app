package com.example.voiceagent.contacts

import android.content.Context
import android.provider.ContactsContract
import java.text.Normalizer
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class ContactMatch(
    val contactId: Long,
    val name: String,
    val number: String,
    val score: Double,
)

class ContactFinder(private val context: Context) {

    suspend fun find(spokenName: String): List<ContactMatch> = withContext(Dispatchers.IO) {
        val query = normalize(spokenName)
        if (query.isEmpty()) return@withContext emptyList()

        val rows = ArrayList<Row>()
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER,
            ContactsContract.CommonDataKinds.Phone.TYPE,
        )

        context.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            projection,
            null,
            null,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC",
        )?.use { cursor ->
            val idIndex = cursor.getColumnIndexOrThrow(
                ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
            )
            val nameIndex = cursor.getColumnIndexOrThrow(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            )
            val numberIndex = cursor.getColumnIndexOrThrow(
                ContactsContract.CommonDataKinds.Phone.NUMBER,
            )
            val typeIndex = cursor.getColumnIndexOrThrow(
                ContactsContract.CommonDataKinds.Phone.TYPE,
            )

            while (cursor.moveToNext()) {
                val name = cursor.getString(nameIndex)?.trim().orEmpty()
                val number = cursor.getString(numberIndex)?.trim().orEmpty()
                if (name.isEmpty() || number.length < MIN_NUMBER_LENGTH) continue
                rows += Row(
                    contactId = cursor.getLong(idIndex),
                    name = name,
                    number = number,
                    type = cursor.getInt(typeIndex),
                    normalizedName = normalize(name),
                )
            }
        }

        if (rows.isEmpty()) return@withContext emptyList()

        val threshold = if (query.length <= 2) 0.9 else MATCH_THRESHOLD
        rows.groupBy { it.contactId }
            .mapNotNull { (contactId, contactRows) ->
                val bestScore = contactRows.maxOf { score(query, it.normalizedName) }
                if (bestScore < threshold) return@mapNotNull null
                val preferred = contactRows.minWithOrNull(
                    compareBy<Row> { typeRank(it.type) }.thenBy { it.number },
                ) ?: return@mapNotNull null
                ContactMatch(
                    contactId = contactId,
                    name = preferred.name,
                    number = preferred.number,
                    score = bestScore,
                )
            }
            .sortedWith(compareByDescending<ContactMatch> { it.score }.thenBy { it.name })
            .take(MAX_MATCHES)
    }

    private fun score(query: String, name: String): Double {
        if (query.isEmpty() || name.isEmpty()) return 0.0
        if (name == query) return 1.0

        val queryTokens = query.split(' ').filter { it.isNotEmpty() }
        val nameTokens = name.split(' ').filter { it.isNotEmpty() }

        if (nameTokens.any { it == query }) return 0.97
        if (name.startsWith(query)) return 0.92
        if (queryTokens.all { token -> nameTokens.any { it == token } }) return 0.90
        if (name.contains(query)) return 0.88
        if (queryTokens.all { token -> nameTokens.any { it.startsWith(token) } }) return 0.84

        val bestToken = nameTokens.maxOfOrNull { similarity(query, it) } ?: 0.0
        val wholeName = similarity(query, name)
        return maxOf(bestToken * 0.95, wholeName)
    }

    private fun similarity(left: String, right: String): Double {
        val longest = maxOf(left.length, right.length)
        if (longest == 0) return 1.0
        return 1.0 - levenshtein(left, right).toDouble() / longest
    }

    private fun levenshtein(left: String, right: String): Int {
        var previous = IntArray(right.length + 1) { it }
        var current = IntArray(right.length + 1)
        for (i in 1..left.length) {
            current[0] = i
            for (j in 1..right.length) {
                val cost = if (left[i - 1] == right[j - 1]) 0 else 1
                current[j] = minOf(
                    current[j - 1] + 1,
                    previous[j] + 1,
                    previous[j - 1] + cost,
                )
            }
            val swap = previous
            previous = current
            current = swap
        }
        return previous[right.length]
    }

    private fun normalize(value: String): String = Normalizer
        .normalize(value, Normalizer.Form.NFD)
        .replace(DIACRITICS, "")
        .lowercase(Locale.ROOT)
        .replace(PUNCTUATION, " ")
        .replace(WHITESPACE, " ")
        .trim()

    private fun typeRank(type: Int): Int = when (type) {
        ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE -> 0
        ContactsContract.CommonDataKinds.Phone.TYPE_HOME -> 1
        ContactsContract.CommonDataKinds.Phone.TYPE_WORK -> 2
        else -> 3
    }

    private data class Row(
        val contactId: Long,
        val name: String,
        val number: String,
        val type: Int,
        val normalizedName: String,
    )

    private companion object {
        const val MATCH_THRESHOLD = 0.55
        const val MAX_MATCHES = 5
        const val MIN_NUMBER_LENGTH = 3
        val DIACRITICS = Regex("\\p{M}+")
        val PUNCTUATION = Regex("[^\\p{L}\\p{N} ]")
        val WHITESPACE = Regex("\\s+")
    }
}

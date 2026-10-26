package com.example.allinonedailer.data

/** Number matching, kept free of Android types so it runs in JVM tests. */
object PhoneNumbers {

    /**
     * The key two spellings of one number share: the last 10 digits, so
     * "+91 98765 43210", "098765 43210" and "9876543210" all match. Indian
     * toll-free (1800…) and shared-cost (1860…) numbers have 11 digits; their
     * last 10 ("800…") would collide with a mobile number, so they keep all
     * of them. Short codes like 121 stay as they are. Empty when there are no
     * digits.
     */
    fun key(number: String): String {
        val national = BlockRules.nationalNumber(number)
        if (national.length == 11 && (national.startsWith("1800") || national.startsWith("1860"))) {
            return national
        }
        return number.filter(Char::isDigit).takeLast(10)
    }

    /** Both are the same number; never true when either has no digits. */
    fun sameNumber(a: String, b: String): Boolean {
        val ka = key(a)
        return ka.isNotEmpty() && ka == key(b)
    }
}

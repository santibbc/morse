package com.morsebt.app

import java.text.Normalizer
import java.util.Locale

/**
 * Codificación y decodificación Morse internacional (con Ñ, ¿ y ¡ del español).
 *
 * Formato de texto Morse usado en toda la app y en el protocolo Bluetooth:
 *   - "." punto, "-" raya
 *   - letras separadas por un espacio
 *   - palabras separadas por " / "
 *   Ejemplo: "HOLA MUNDO" -> ".... --- .-.. .- / -- ..- -. -.. ---"
 */
object MorseCode {

    val TABLE: Map<Char, String> = mapOf(
        'A' to ".-", 'B' to "-...", 'C' to "-.-.", 'D' to "-..", 'E' to ".",
        'F' to "..-.", 'G' to "--.", 'H' to "....", 'I' to "..", 'J' to ".---",
        'K' to "-.-", 'L' to ".-..", 'M' to "--", 'N' to "-.", 'Ñ' to "--.--",
        'O' to "---", 'P' to ".--.", 'Q' to "--.-", 'R' to ".-.", 'S' to "...",
        'T' to "-", 'U' to "..-", 'V' to "...-", 'W' to ".--", 'X' to "-..-",
        'Y' to "-.--", 'Z' to "--..",
        '0' to "-----", '1' to ".----", '2' to "..---", '3' to "...--", '4' to "....-",
        '5' to ".....", '6' to "-....", '7' to "--...", '8' to "---..", '9' to "----.",
        '.' to ".-.-.-", ',' to "--..--", '?' to "..--..", '\'' to ".----.",
        '!' to "-.-.--", '/' to "-..-.", '(' to "-.--.", ')' to "-.--.-",
        '&' to ".-...", ':' to "---...", ';' to "-.-.-.", '=' to "-...-",
        '+' to ".-.-.", '-' to "-....-", '_' to "..--.-", '"' to ".-..-.",
        '$' to "...-..-", '@' to ".--.-.", '¿' to "..-.-", '¡' to "--...-"
    )

    private val REVERSE: Map<String, Char> = TABLE.entries.associate { (k, v) -> v to k }

    /** Mayúsculas y sin tildes (se conserva la Ñ). */
    fun normalize(text: String): String {
        val sb = StringBuilder()
        for (ch in text.uppercase(Locale.ROOT)) {
            if (ch == 'Ñ') {
                sb.append(ch)
            } else {
                sb.append(
                    Normalizer.normalize(ch.toString(), Normalizer.Form.NFD)
                        .replace(Regex("\\p{Mn}+"), "")
                )
            }
        }
        return sb.toString()
    }

    /** Texto -> Morse. Los caracteres sin equivalente se omiten. */
    fun encode(text: String): String =
        normalize(text).trim()
            .split(Regex("\\s+"))
            .mapNotNull { word ->
                val letters = word.mapNotNull { TABLE[it] }
                if (letters.isEmpty()) null else letters.joinToString(" ")
            }
            .joinToString(" / ")

    /** Morse -> texto. Las secuencias desconocidas se muestran como '?'. */
    fun decode(morse: String): String {
        val sb = StringBuilder()
        for (token in morse.trim().split(Regex("\\s+"))) {
            if (token.isEmpty()) continue
            if (token == "/") {
                if (sb.isNotEmpty() && sb.last() != ' ') sb.append(' ')
            } else {
                sb.append(REVERSE[token] ?: '?')
            }
        }
        return sb.toString().trim()
    }

    fun decodeLetter(symbols: String): Char? = REVERSE[symbols]

    /** Para mostrar en pantalla: · y − en lugar de . y - */
    fun pretty(morse: String): String = morse.replace('.', '·').replace('-', '−')

    /**
     * Línea de tiempo en "unidades" Morse: lista de (sonando, duración).
     * punto = 1, raya = 3, entre símbolos = 1, entre letras = 3, entre palabras = 7.
     */
    fun timeline(morse: String): List<Pair<Boolean, Int>> {
        val out = mutableListOf<Pair<Boolean, Int>>()
        var pendingGap = 0
        for (token in morse.trim().split(Regex("\\s+"))) {
            if (token.isEmpty()) continue
            if (token == "/") {
                if (out.isNotEmpty()) pendingGap = 7
                continue
            }
            val symbols = token.filter { it == '.' || it == '-' }
            if (symbols.isEmpty()) continue
            if (out.isNotEmpty()) out.add(false to maxOf(pendingGap, 3))
            pendingGap = 0
            symbols.forEachIndexed { i, c ->
                if (i > 0) out.add(false to 1)
                out.add(true to if (c == '.') 1 else 3)
            }
        }
        return out
    }
}

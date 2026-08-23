package netease

import kotlin.math.abs
import kotlin.math.sin

/**
 * 网易云 EAPI 加密所需的纯 Kotlin 密码学原语（Kotlin/Native 无 javax.crypto）。
 *
 * 算法实现已通过标准测试向量验证：
 * - MD5("abc") = 900150983cd24fb0d6963f7d28e17f72
 * - AES-128(key=000102..0f, pt=00112233445566778899aabbccddeeff) = 69c4e0d86a7b0430d8cdb78070b4c55a
 */

private const val EAPI_KEY = "e82ckenh8dichen8"
private const val EAPI_MAGIC = "36cd479b6b5"

/** EAPI 请求体变换：`params=<大写十六进制(AES-128-ECB(拼接串))>`。 */
internal fun eapiParams(
    path: String,
    jsonPayload: String,
): String {
    val digest = md5Hex("nobody${path}use${jsonPayload}md5forencrypt".encodeToByteArray())
    val text = "$path-$EAPI_MAGIC-$jsonPayload-$EAPI_MAGIC-$digest"
    return "params=" + aes128EcbEncrypt(text.encodeToByteArray(), EAPI_KEY.encodeToByteArray()).toHex(uppercase = true)
}

internal fun ByteArray.toHex(uppercase: Boolean = false): String {
    val digits = if (uppercase) "0123456789ABCDEF" else "0123456789abcdef"
    val out = CharArray(size * 2)
    for (i in indices) {
        val b = this[i].toInt() and 0xFF
        out[2 * i] = digits[b shr 4]
        out[2 * i + 1] = digits[b and 0x0F]
    }
    return out.concatToString()
}

// ---------- MD5 ----------

private val MD5_SHIFTS =
    intArrayOf(
        7,
        12,
        17,
        22,
        7,
        12,
        17,
        22,
        7,
        12,
        17,
        22,
        7,
        12,
        17,
        22,
        5,
        9,
        14,
        20,
        5,
        9,
        14,
        20,
        5,
        9,
        14,
        20,
        5,
        9,
        14,
        20,
        4,
        11,
        16,
        23,
        4,
        11,
        16,
        23,
        4,
        11,
        16,
        23,
        4,
        11,
        16,
        23,
        6,
        10,
        15,
        21,
        6,
        10,
        15,
        21,
        6,
        10,
        15,
        21,
        6,
        10,
        15,
        21,
    )

internal fun md5Hex(input: ByteArray): String {
    // K[i] = floor(abs(sin(i+1)) * 2^32)，运行时生成避免抄表出错
    val k = UIntArray(64) { i -> (abs(sin(i + 1.0)) * 4294967296.0).toUInt() }

    val bitLength = input.size.toLong() * 8
    val padded = ArrayList<Byte>(input.size + 72)
    input.forEach { padded.add(it) }
    padded.add(0x80.toByte())
    while (padded.size % 64 != 56) padded.add(0)
    for (shift in 0 until 64 step 8) padded.add(((bitLength ushr shift) and 0xFF).toByte())

    var a0 = 0x67452301u
    var b0 = 0xefcdab89u
    var c0 = 0x98badcfeu
    var d0 = 0x10325476u

    var offset = 0
    while (offset < padded.size) {
        val m =
            UIntArray(16) { i ->
                val p = offset + 4 * i
                (padded[p].toUInt() and 0xFFu) or
                    ((padded[p + 1].toUInt() and 0xFFu) shl 8) or
                    ((padded[p + 2].toUInt() and 0xFFu) shl 16) or
                    ((padded[p + 3].toUInt() and 0xFFu) shl 24)
            }
        var a = a0
        var b = b0
        var c = c0
        var d = d0
        for (i in 0 until 64) {
            var f: UInt
            val g: Int
            when (i / 16) {
                0 -> {
                    f = (b and c) or (b.inv() and d)
                    g = i
                }

                1 -> {
                    f = (d and b) or (d.inv() and c)
                    g = (5 * i + 1) % 16
                }

                2 -> {
                    f = b xor c xor d
                    g = (3 * i + 5) % 16
                }

                else -> {
                    f = c xor (b or d.inv())
                    g = (7 * i) % 16
                }
            }
            f = f + a + k[i] + m[g]
            a = d
            d = c
            c = b
            b = b + f.rotateLeft(MD5_SHIFTS[i])
        }
        a0 += a
        b0 += b
        c0 += c
        d0 += d
        offset += 64
    }

    val digest = ByteArray(16)
    var i = 0
    for (word in uintArrayOf(a0, b0, c0, d0)) {
        for (shift in 0 until 32 step 8) {
            digest[i++] = ((word shr shift) and 0xFFu).toByte()
        }
    }
    return digest.toHex()
}

// ---------- AES-128 ECB 加密 ----------

private fun gfMul(
    a0: Int,
    b0: Int,
): Int {
    var a = a0
    var b = b0
    var p = 0
    repeat(8) {
        if (b and 1 != 0) p = p xor a
        val hi = a and 0x80
        a = (a shl 1) and 0xFF
        if (hi != 0) a = a xor 0x1B
        b = b shr 1
    }
    return p
}

private fun gfPow(
    base0: Int,
    e0: Int,
): Int {
    var r = 1
    var base = base0
    var e = e0
    while (e > 0) {
        if (e and 1 != 0) r = gfMul(r, base)
        base = gfMul(base, base)
        e = e shr 1
    }
    return r
}

private fun rotl8(
    x: Int,
    n: Int,
): Int = ((x shl n) or (x shr (8 - n))) and 0xFF

// S-box 运行时生成：乘法逆元 + 仿射变换，避免 256 项查表抄错
private val aesSbox: IntArray by lazy {
    IntArray(256) { x ->
        val inv = if (x == 0) 0 else gfPow(x, 254)
        inv xor rotl8(inv, 1) xor rotl8(inv, 2) xor rotl8(inv, 3) xor rotl8(inv, 4) xor 0x63
    }
}

private val AES_RCON = intArrayOf(1, 2, 4, 8, 16, 32, 64, 128, 27, 54)

/** AES-128-ECB 加密，始终附加 PKCS7 填充（与网易云客户端 encryptECB 行为一致）。 */
internal fun aes128EcbEncrypt(
    data: ByteArray,
    key: ByteArray,
): ByteArray {
    require(key.size == 16) { "AES-128 密钥必须为 16 字节" }
    val sbox = aesSbox

    // 密钥扩展：11 组轮密钥
    val w = ByteArray(176)
    key.copyInto(w, 0, 0, 16)
    var i = 16
    while (i < 176) {
        var t0 = w[i - 4].toInt() and 0xFF
        var t1 = w[i - 3].toInt() and 0xFF
        var t2 = w[i - 2].toInt() and 0xFF
        var t3 = w[i - 1].toInt() and 0xFF
        if (i % 16 == 0) {
            val r0 = sbox[t1] xor AES_RCON[i / 16 - 1]
            val r1 = sbox[t2]
            val r2 = sbox[t3]
            val r3 = sbox[t0]
            t0 = r0
            t1 = r1
            t2 = r2
            t3 = r3
        }
        w[i] = ((w[i - 16].toInt() and 0xFF) xor t0).toByte()
        w[i + 1] = ((w[i - 15].toInt() and 0xFF) xor t1).toByte()
        w[i + 2] = ((w[i - 14].toInt() and 0xFF) xor t2).toByte()
        w[i + 3] = ((w[i - 13].toInt() and 0xFF) xor t3).toByte()
        i += 4
    }

    val pad = 16 - data.size % 16
    val padded = data + ByteArray(pad) { pad.toByte() }
    val out = ByteArray(padded.size)
    var offset = 0
    while (offset < padded.size) {
        aesEncryptBlock(padded, offset, out, offset, w, sbox)
        offset += 16
    }
    return out
}

private fun aesEncryptBlock(
    input: ByteArray,
    inOffset: Int,
    output: ByteArray,
    outOffset: Int,
    w: ByteArray,
    sbox: IntArray,
) {
    val s = IntArray(16) { input[inOffset + it].toInt() and 0xFF }

    fun addRoundKey(round: Int) {
        for (j in 0 until 16) s[j] = s[j] xor (w[16 * round + j].toInt() and 0xFF)
    }

    addRoundKey(0)
    for (round in 1..10) {
        for (j in 0 until 16) s[j] = sbox[s[j]]
        // ShiftRows（列主序 state）
        val t = s.copyOf()
        s[1] = t[5]
        s[5] = t[9]
        s[9] = t[13]
        s[13] = t[1]
        s[2] = t[10]
        s[6] = t[14]
        s[10] = t[2]
        s[14] = t[6]
        s[3] = t[15]
        s[7] = t[3]
        s[11] = t[7]
        s[15] = t[11]
        if (round < 10) {
            // MixColumns
            for (c in 0 until 4) {
                val i0 = 4 * c
                val x0 = s[i0]
                val x1 = s[i0 + 1]
                val x2 = s[i0 + 2]
                val x3 = s[i0 + 3]
                s[i0] = gfMul(x0, 2) xor gfMul(x1, 3) xor x2 xor x3
                s[i0 + 1] = x0 xor gfMul(x1, 2) xor gfMul(x2, 3) xor x3
                s[i0 + 2] = x0 xor x1 xor gfMul(x2, 2) xor gfMul(x3, 3)
                s[i0 + 3] = gfMul(x0, 3) xor x1 xor x2 xor gfMul(x3, 2)
            }
        }
        addRoundKey(round)
    }
    for (j in 0 until 16) output[outOffset + j] = s[j].toByte()
}

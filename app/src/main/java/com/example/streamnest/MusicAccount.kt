package com.example.streamnest

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * One profile's choice on the "Bring your YouTube Music" page.
 * mode: "" = not asked yet, "own" = Xstream's own library, "google" = signed in.
 */
@Serializable
data class MusicAccountData(
    val mode: String = "",
    val cookie: String = "",
    /** Shown in Settings as "signed in as ...". Filled in later (Part 5) once we can ask YouTube. */
    val label: String = "",
    val signedAt: Long = 0L,
) {
    val signedIn: Boolean get() = mode == "google" && cookie.isNotBlank()
}

/**
 * Per-profile account store. One encrypted file per profile in filesDir/music-account/.
 * The key lives in the Android Keystore, so the file is useless if copied off the phone.
 * Cookie values are never written to MusicLog or ActLog.
 */
object MusicAccount {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private const val ALIAS = "appx_music_account"

    private val _current = MutableStateFlow(MusicAccountData())
    /** The account of the profile that is open right now. */
    val current: StateFlow<MusicAccountData> = _current

    @Volatile var activeId: String? = null
        private set

    private val lock = Mutex()

    /** The cookie header value for the open profile, or null when not signed in. */
    fun activeCookie(): String? = _current.value.takeIf { it.signedIn }?.cookie

    private fun file(ctx: Context, id: String): File {
        val dir = File(ctx.filesDir, "music-account").apply { mkdirs() }
        return File(dir, id.replace(Regex("[^A-Za-z0-9_-]"), "_") + ".bin")
    }

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    private fun encrypt(plain: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key())
        val iv = c.iv
        return byteArrayOf(iv.size.toByte()) + iv + c.doFinal(plain)
    }

    private fun decrypt(b: ByteArray): ByteArray {
        val n = b[0].toInt()
        val iv = b.copyOfRange(1, 1 + n)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
        return c.doFinal(b, 1 + n, b.size - 1 - n)
    }

    private suspend fun read(ctx: Context, id: String): MusicAccountData = withContext(Dispatchers.IO) {
        val f = file(ctx, id)
        if (!f.exists()) return@withContext MusicAccountData()
        runCatching { json.decodeFromString<MusicAccountData>(String(decrypt(f.readBytes()), Charsets.UTF_8)) }
            .onFailure { MusicLog.add("Account: could not read the saved sign-in for profile $id, treating it as signed out") }
            .getOrDefault(MusicAccountData())
    }

    private suspend fun write(ctx: Context, id: String, d: MusicAccountData) = withContext(Dispatchers.IO) {
        runCatching {
            val f = file(ctx, id)
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeBytes(encrypt(json.encodeToString(d).toByteArray(Charsets.UTF_8)))
            if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
        }.onFailure { MusicLog.add("Account: could not save for profile $id (${it.message})") }
    }

    /** Makes this profile the open one and returns its saved choice. */
    suspend fun use(ctx: Context, id: String): MusicAccountData = lock.withLock {
        val app = ctx.applicationContext
        val d = read(app, id)
        activeId = id
        _current.value = d
        MusicLog.add("Account: profile $id opened (${when { d.signedIn -> "signed in"; d.mode == "own" -> "Xstream's own library"; else -> "not asked yet" }})")
        d
    }

    private suspend fun update(ctx: Context, id: String, block: (MusicAccountData) -> MusicAccountData) = lock.withLock {
        val app = ctx.applicationContext
        val d = block(read(app, id))
        write(app, id, d)
        if (activeId == id) _current.value = d
    }

    suspend fun chooseOwn(ctx: Context, id: String) {
        update(ctx, id) { it.copy(mode = "own", cookie = "", label = "", signedAt = 0L) }
        MusicLog.add("Account: profile $id chose Xstream's own library")
    }

    suspend fun signIn(ctx: Context, id: String, cookie: String) {
        update(ctx, id) { it.copy(mode = "google", cookie = cookie, signedAt = System.currentTimeMillis()) }
        MusicLog.add("Account: profile $id signed in with Google (cookie saved on this phone only)")
    }

    suspend fun setLabel(ctx: Context, id: String, label: String) {
        update(ctx, id) { it.copy(label = label) }
    }

    /** Settings > Sign in: shows the "Bring your YouTube Music" page again for this profile. */
    suspend fun askAgain(ctx: Context, id: String) {
        update(ctx, id) { it.copy(mode = "", cookie = "", label = "", signedAt = 0L) }
        MusicLog.add("Account: profile $id will be asked about YouTube Music again")
    }

    /** Forgets the cookie. The profile goes back to Xstream's own library and is not asked again. */
    suspend fun signOut(ctx: Context, id: String) {
        update(ctx, id) { it.copy(mode = "own", cookie = "", label = "", signedAt = 0L) }
        MusicLog.add("Account: profile $id signed out, back to Xstream's own library")
    }
}

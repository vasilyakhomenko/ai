package app.connector

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import androidx.room.*
import kotlinx.coroutines.flow.Flow
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

private val Context.dataStore by preferencesDataStore("glasschat_settings")

@Entity(tableName = "chats")
data class ChatEntity(
    @PrimaryKey val id: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long
)

@Entity(tableName = "messages", foreignKeys = [ForeignKey(entity = ChatEntity::class, parentColumns = ["id"], childColumns = ["chatId"], onDelete = ForeignKey.CASCADE)], indices = [Index("chatId")])
data class MessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val chatId: String,
    val role: String,
    val content: String,
    val createdAt: Long = System.currentTimeMillis()
)

@Dao
interface ChatDao {
    @Query("SELECT * FROM chats ORDER BY updatedAt DESC") fun observeChats(): Flow<List<ChatEntity>>
    @Query("SELECT * FROM messages WHERE chatId = :chatId ORDER BY createdAt, id") fun observeMessages(chatId: String): Flow<List<MessageEntity>>
    @Query("SELECT * FROM chats WHERE id = :id LIMIT 1") suspend fun getChat(id: String): ChatEntity?
    @Insert suspend fun insertChat(chat: ChatEntity)
    @Update suspend fun updateChat(chat: ChatEntity)
    @Delete suspend fun deleteChat(chat: ChatEntity)
    @Insert suspend fun insertMessage(message: MessageEntity): Long
    @Query("UPDATE messages SET content = :content WHERE id = :id") suspend fun updateMessage(id: Long, content: String)
    @Query("DELETE FROM messages WHERE id = :id") suspend fun deleteMessage(id: Long)
    @Query("DELETE FROM messages WHERE chatId = :chatId") suspend fun clearMessages(chatId: String)
}

@Database(entities = [ChatEntity::class, MessageEntity::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun chatDao(): ChatDao
    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null
        fun get(context: Context): AppDatabase = INSTANCE ?: synchronized(this) {
            INSTANCE ?: Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "glasschat.db").build().also { INSTANCE = it }
        }
    }
}

class SettingsStore(private val context: Context) {
    private val base = stringPreferencesKey("base_url")
    private val model = stringPreferencesKey("model")
    private val prompt = stringPreferencesKey("system_prompt")
    private val temperature = floatPreferencesKey("temperature")
    val flow = context.dataStore.data
    suspend fun save(baseUrl: String, modelId: String, systemPrompt: String, temp: Float) {
        context.dataStore.edit { it[base] = baseUrl; it[model] = modelId; it[prompt] = systemPrompt; it[temperature] = temp }
    }
    fun read(p: Preferences) = ModelSettings(p[base] ?: "https://api.openai.com/v1", p[model] ?: "gpt-4o-mini", p[prompt] ?: "You are a helpful assistant. Answer in the user's language.", p[temperature] ?: 0.7f)
}
data class ModelSettings(val baseUrl: String, val model: String, val systemPrompt: String, val temperature: Float)

/** AES/GCM key is generated and retained by Android Keystore; plaintext API keys are never persisted. */
class ApiKeyVault(context: Context) {
    private val prefs = context.getSharedPreferences("secret_vault", Context.MODE_PRIVATE)
    private val alias = "glasschat_api_key_v1"
    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(alias, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setRandomizedEncryptionRequired(true).build())
        return gen.generateKey()
    }
    fun save(value: String) {
        if (value.isBlank()) { prefs.edit().remove("cipher").apply(); return }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        prefs.edit().putString("iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP)).putString("cipher", Base64.encodeToString(encrypted, Base64.NO_WRAP)).apply()
    }
    fun read(): String {
        val iv = prefs.getString("iv", null) ?: return ""
        val data = prefs.getString("cipher", null) ?: return ""
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)))
            String(cipher.doFinal(Base64.decode(data, Base64.NO_WRAP)), Charsets.UTF_8)
        } catch (_: Exception) { "" }
    }
}

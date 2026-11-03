package org.crisislink.storage

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import org.crisislink.core.model.Ack
import org.crisislink.core.model.Contact
import org.crisislink.core.model.DeliveryStatus
import org.crisislink.core.model.Message
import org.crisislink.core.model.Priority
import org.crisislink.core.model.PublicKeys
import org.crisislink.core.storage.MeshStore

@Entity(tableName = "messages")
class MessageRow(
    @PrimaryKey val id: String,
    val sourceId: String,
    val destinationId: String?,
    val priority: Int,
    val createdAtMs: Long,
    val ttlSeconds: Int,
    val encKey: ByteArray,
    val signKey: ByteArray,
    val payload: ByteArray,
    val signature: ByteArray,
    val hopCount: Int,
    val sprayTokens: Int,
    val copiesMade: Int,
    val status: Int,
    val text: String?,
    @ColumnInfo(defaultValue = "0") val firstSeenMs: Long,
    @ColumnInfo(defaultValue = "0") val ageAtReceiptMs: Long,
)

@Entity(tableName = "acks")
class AckRow(
    @PrimaryKey val messageId: String,
    val recipientId: String,
    val encKey: ByteArray,
    val signKey: ByteArray,
    val createdAtMs: Long,
    val signature: ByteArray,
)

@Entity(tableName = "contacts")
class ContactRow(@PrimaryKey val nodeId: String, val name: String, val encKey: ByteArray, val signKey: ByteArray, val verified: Boolean)

@Entity(tableName = "encounters")
class EncounterRow(@PrimaryKey val nodeId: String, val p: Double)

@Entity(tableName = "meta")
class MetaRow(@PrimaryKey val key: String, val value: Long)

@Dao
interface MeshDao {
    @Query("SELECT * FROM messages") fun messages(): List<MessageRow>
    @Query("SELECT * FROM messages WHERE id = :id") fun message(id: String): MessageRow?
    @Insert(onConflict = OnConflictStrategy.REPLACE) fun put(row: MessageRow)
    @Query("DELETE FROM messages WHERE id = :id") fun deleteMessage(id: String)

    @Query("SELECT * FROM acks") fun acks(): List<AckRow>
    @Query("SELECT COUNT(*) FROM acks WHERE messageId = :id") fun ackCount(id: String): Int
    @Insert(onConflict = OnConflictStrategy.REPLACE) fun put(row: AckRow)
    @Query("DELETE FROM acks WHERE createdAtMs < :cutoff") fun deleteAcksBefore(cutoff: Long)

    @Query("SELECT * FROM contacts ORDER BY name") fun contacts(): List<ContactRow>
    @Query("SELECT * FROM contacts WHERE nodeId = :id") fun contact(id: String): ContactRow?
    @Insert(onConflict = OnConflictStrategy.REPLACE) fun put(row: ContactRow)
    @Query("DELETE FROM contacts WHERE nodeId = :id") fun deleteContact(id: String)

    @Query("SELECT * FROM encounters") fun encounters(): List<EncounterRow>
    @Query("DELETE FROM encounters") fun clearEncounters()
    @Insert(onConflict = OnConflictStrategy.REPLACE) fun putAll(rows: List<EncounterRow>)
    @Query("SELECT value FROM meta WHERE `key` = 'encounters_at'") fun encountersAt(): Long?
    @Insert(onConflict = OnConflictStrategy.REPLACE) fun put(row: MetaRow)
}

@Database(
    entities = [MessageRow::class, AckRow::class, ContactRow::class, EncounterRow::class, MetaRow::class],
    version = 2,
    exportSchema = false,
)
abstract class CrisisLinkDb : RoomDatabase() {
    abstract fun dao(): MeshDao
}

/** Messages now track their own age instead of trusting the sender's clock. */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE messages ADD COLUMN firstSeenMs INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE messages ADD COLUMN ageAtReceiptMs INTEGER NOT NULL DEFAULT 0")
        db.execSQL("UPDATE messages SET firstSeenMs = createdAtMs")
    }
}

class RoomMeshStore(private val dao: MeshDao) : MeshStore {
    override fun messages() = dao.messages().map { it.toModel() }
    override fun message(id: String) = dao.message(id)?.toModel()
    override fun upsert(message: Message) = dao.put(message.toRow())
    override fun delete(id: String) = dao.deleteMessage(id)

    override fun acks() = dao.acks().map { Ack(it.messageId, it.recipientId, PublicKeys(it.encKey, it.signKey), it.createdAtMs, it.signature) }
    override fun hasAck(messageId: String) = dao.ackCount(messageId) > 0
    override fun putAck(ack: Ack) = dao.put(AckRow(ack.messageId, ack.recipientId, ack.recipientKeys.encryption, ack.recipientKeys.signing, ack.createdAtMs, ack.signature))
    override fun deleteAcksOlderThan(cutoffMs: Long) = dao.deleteAcksBefore(cutoffMs)

    override fun contacts() = dao.contacts().map { it.toModel() }
    override fun contact(nodeId: String) = dao.contact(nodeId)?.toModel()
    override fun putContact(contact: Contact) = dao.put(ContactRow(contact.nodeId, contact.name, contact.keys.encryption, contact.keys.signing, contact.verified))
    override fun deleteContact(nodeId: String) = dao.deleteContact(nodeId)

    override fun loadEncounters(): Pair<Map<String, Double>, Long>? {
        val at = dao.encountersAt() ?: return null
        return dao.encounters().associate { it.nodeId to it.p } to at
    }

    override fun saveEncounters(table: Map<String, Double>, updatedAtMs: Long) {
        dao.clearEncounters()
        dao.putAll(table.map { EncounterRow(it.key, it.value) })
        dao.put(MetaRow("encounters_at", updatedAtMs))
    }

    private fun ContactRow.toModel() = Contact(nodeId, name, PublicKeys(encKey, signKey), verified)

    private fun MessageRow.toModel() = Message(
        id = id, sourceId = sourceId, destinationId = destinationId, priority = Priority.entries[priority],
        createdAtMs = createdAtMs, ttlSeconds = ttlSeconds, sourceKeys = PublicKeys(encKey, signKey),
        payload = payload, signature = signature, hopCount = hopCount, sprayTokens = sprayTokens,
        copiesMade = copiesMade, status = DeliveryStatus.entries[status], text = text,
        firstSeenMs = firstSeenMs, ageAtReceiptMs = ageAtReceiptMs,
    )

    private fun Message.toRow() = MessageRow(
        id, sourceId, destinationId, priority.ordinal, createdAtMs, ttlSeconds, sourceKeys.encryption, sourceKeys.signing,
        payload, signature, hopCount, sprayTokens, copiesMade, status.ordinal, text, firstSeenMs, ageAtReceiptMs,
    )
}

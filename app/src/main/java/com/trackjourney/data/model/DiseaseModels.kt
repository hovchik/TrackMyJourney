package com.trackjourney.data.model

import androidx.room.*
import java.util.UUID

// ─────────────────────────────────────────────────────────
//  PERSON — Users who can have diseases tracked
// ─────────────────────────────────────────────────────────

@Entity(tableName = "persons")
data class Person(
    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),

    @ColumnInfo(name = "name")
    val name: String = "",

    @ColumnInfo(name = "relationship")
    val relationship: String = "",  // e.g. "Self", "Spouse", "Child", "Parent"

    @ColumnInfo(name = "is_self")
    val isSelf: Boolean = false,

    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis()
)

// ─────────────────────────────────────────────────────────
//  DISEASE GROUP — For grouping diseases by category
// ─────────────────────────────────────────────────────────

@Entity(tableName = "disease_groups")
data class DiseaseGroup(
    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),

    @ColumnInfo(name = "name")
    val name: String = "",

    @ColumnInfo(name = "color_hex")
    val colorHex: Long = 0xFF607D8B,

    @ColumnInfo(name = "icon_name")
    val iconName: String = "Healing",

    @ColumnInfo(name = "sort_order")
    val sortOrder: Int = 0,

    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis()
)

// ─────────────────────────────────────────────────────────
//  DISEASE — Individual disease entry with soft delete
// ─────────────────────────────────────────────────────────

@Entity(
    tableName = "diseases",
    foreignKeys = [
        ForeignKey(
            entity = Person::class,
            parentColumns = ["id"],
            childColumns = ["person_id"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = DiseaseGroup::class,
            parentColumns = ["id"],
            childColumns = ["group_id"],
            onDelete = ForeignKey.SET_NULL
        )
    ],
    indices = [
        Index(value = ["person_id"]),
        Index(value = ["group_id"]),
        Index(value = ["is_deleted"])
    ]
)
data class Disease(
    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),

    @ColumnInfo(name = "name")
    val name: String = "",

    @ColumnInfo(name = "description")
    val description: String = "",

    @ColumnInfo(name = "person_id")
    val personId: String,

    @ColumnInfo(name = "group_id")
    val groupId: String? = null,

    @ColumnInfo(name = "severity")
    val severity: DiseaseSeverity = DiseaseSeverity.MILD,

    @ColumnInfo(name = "status")
    val status: DiseaseStatus = DiseaseStatus.ACTIVE,

    @ColumnInfo(name = "onset_date")
    val onsetDate: Long? = null,

    @ColumnInfo(name = "recovery_date")
    val recoveryDate: Long? = null,

    @ColumnInfo(name = "notes")
    val notes: String = "",

    @ColumnInfo(name = "is_deleted")
    val isDeleted: Boolean = false,

    @ColumnInfo(name = "deleted_at")
    val deletedAt: Long? = null,

    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis(),

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long = System.currentTimeMillis()
)

// ─────────────────────────────────────────────────────────
//  DISEASE ENUMS
// ─────────────────────────────────────────────────────────

enum class DiseaseSeverity(val label: String, val colorHex: Long) {
    MILD("Mild", 0xFF4CAF50),
    MODERATE("Moderate", 0xFFFF9800),
    SEVERE("Severe", 0xFFE53935),
    CRITICAL("Critical", 0xFF9C27B0)
}

enum class DiseaseStatus(val label: String) {
    ACTIVE("Active"),
    RECOVERING("Recovering"),
    RECOVERED("Recovered"),
    CHRONIC("Chronic")
}

// ─────────────────────────────────────────────────────────
//  RELATION HELPERS
// ─────────────────────────────────────────────────────────

data class DiseaseWithGroup(
    @Embedded val disease: Disease,
    @Relation(parentColumn = "group_id", entityColumn = "id")
    val group: DiseaseGroup?
)

data class PersonWithDiseases(
    @Embedded val person: Person,
    @Relation(parentColumn = "id", entityColumn = "person_id")
    val diseases: List<Disease>
)

package com.bydmate.app.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "vehicle_write_log")
data class VehicleWriteLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val ts: Long,
    val actionName: String,
    val dev: Int,
    val fid: Int,
    val requested: Int,
    val readback: Int?,
    val status: Int,
    val error: String?,
    val validated: Boolean,
)

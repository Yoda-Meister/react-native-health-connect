package dev.matinzd.healthconnect



import android.content.Intent
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.request.ChangesTokenRequest
import androidx.health.connect.client.records.*
import androidx.health.connect.client.changes.DeletionChange
import androidx.health.connect.client.changes.UpsertionChange
import androidx.health.connect.client.response.ChangesResponse
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.WritableArray
import com.facebook.react.bridge.WritableMap
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.ReadableArray
import com.facebook.react.bridge.ReadableMap
import dev.matinzd.healthconnect.permissions.HealthConnectPermissionDelegate
import dev.matinzd.healthconnect.permissions.PermissionUtils
import dev.matinzd.healthconnect.records.ReactHealthRecord
import dev.matinzd.healthconnect.utils.ClientNotInitialized
import dev.matinzd.healthconnect.utils.getTimeRangeFilter
import dev.matinzd.healthconnect.utils.reactRecordTypeToClassMap
import dev.matinzd.healthconnect.utils.rejectWithException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay

class HealthConnectManager(private val applicationContext: ReactApplicationContext) {
  private lateinit var healthConnectClient: HealthConnectClient
  private val coroutineScope = CoroutineScope(Dispatchers.IO)

  private val isInitialized get() = this::healthConnectClient.isInitialized

  private inline fun throwUnlessClientIsAvailable(promise: Promise, block: () -> Unit) {
    if (!isInitialized) {
      return promise.rejectWithException(ClientNotInitialized())
    }
    block()
  }

  fun openHealthConnectSettings() {
    val intent = Intent(HealthConnectClient.ACTION_HEALTH_CONNECT_SETTINGS)
    applicationContext.currentActivity?.startActivity(intent)
  }

  fun openHealthConnectDataManagement(providerPackageName: String?) {
    val intent = providerPackageName?.let {
      HealthConnectClient.getHealthConnectManageDataIntent(applicationContext, it)
    } ?: HealthConnectClient.getHealthConnectManageDataIntent(applicationContext)
    applicationContext.currentActivity?.startActivity(intent)
  }

  fun getSdkStatus(providerPackageName: String, promise: Promise) {
    val status = HealthConnectClient.getSdkStatus(applicationContext, providerPackageName)
    return promise.resolve(status)
  }

  fun initialize(providerPackageName: String, promise: Promise) {
    try {
      healthConnectClient = HealthConnectClient.getOrCreate(applicationContext, providerPackageName)
      promise.resolve(true)
    } catch (e: Exception) {
      promise.rejectWithException(e)
    }
  }

  fun requestPermission(
    reactPermissions: ReadableArray, providerPackageName: String, promise: Promise
  ) {
    throwUnlessClientIsAvailable(promise) {
      coroutineScope.launch {
        val granted = HealthConnectPermissionDelegate.launch(PermissionUtils.parsePermissions(reactPermissions))
        promise.resolve(PermissionUtils.mapPermissionResult(granted))
      }
    }
  }

  fun revokeAllPermissions(promise: Promise) {
    throwUnlessClientIsAvailable(promise) {
      coroutineScope.launch {
        healthConnectClient.permissionController.revokeAllPermissions()
      }
    }
  }

  fun getGrantedPermissions(promise: Promise) {
    throwUnlessClientIsAvailable(promise) {
      coroutineScope.launch {
        promise.resolve(PermissionUtils.getGrantedPermissions(healthConnectClient.permissionController))
      }
    }
  }

  fun insertRecords(reactRecords: ReadableArray, promise: Promise) {
    throwUnlessClientIsAvailable(promise) {
      coroutineScope.launch {
        try {
          val records = ReactHealthRecord.parseWriteRecords(reactRecords)
          val response = healthConnectClient.insertRecords(records)
          promise.resolve(ReactHealthRecord.parseWriteResponse(response))
        } catch (e: Exception) {
          promise.rejectWithException(e)
        }
      }
    }
  }

  fun readRecords(recordType: String, options: ReadableMap, promise: Promise) {
    throwUnlessClientIsAvailable(promise) {
      coroutineScope.launch {
        try {
          val request = ReactHealthRecord.parseReadRequest(recordType, options)
          val response = healthConnectClient.readRecords(request)
          promise.resolve(ReactHealthRecord.parseRecords(recordType, response))
        } catch (e: Exception) {
          promise.rejectWithException(e)
        }
      }
    }
  }

  fun readRecord(recordType: String, recordId: String, promise: Promise) {
    throwUnlessClientIsAvailable(promise) {
      coroutineScope.launch {
        try {
          val record = ReactHealthRecord.getRecordByType(recordType)
          val response = healthConnectClient.readRecord(record, recordId)
          promise.resolve(ReactHealthRecord.parseRecord(recordType, response))
        } catch (e: Exception) {
          promise.rejectWithException(e)
        }
      }
    }
  }

  fun aggregateRecord(record: ReadableMap, promise: Promise) {
    throwUnlessClientIsAvailable(promise) {
      coroutineScope.launch {
        try {
          val recordType = record.getString("recordType") ?: ""
          val response = healthConnectClient.aggregate(
            ReactHealthRecord.getAggregateRequest(
              recordType, record
            )
          )
          promise.resolve(ReactHealthRecord.parseAggregationResult(recordType, response))
        } catch (e: Exception) {
          promise.rejectWithException(e)
        }
      }
    }
  }

  fun deleteRecordsByUuids(
    recordType: String,
    recordIdsList: ReadableArray,
    clientRecordIdsList: ReadableArray,
    promise: Promise
  ) {
    throwUnlessClientIsAvailable(promise) {
      coroutineScope.launch {
        val record = reactRecordTypeToClassMap[recordType]
        if (record != null) {
          healthConnectClient.deleteRecords(
            recordType = record,
            recordIdsList = recordIdsList.toArrayList().mapNotNull { it.toString() }.toList(),
            clientRecordIdsList = if (clientRecordIdsList.size() > 0) clientRecordIdsList.toArrayList()
              .mapNotNull { it.toString() }.toList() else emptyList()
          )
        }
      }
    }
  }

  fun deleteRecordsByTimeRange(
    recordType: String, timeRangeFilter: ReadableMap, promise: Promise
  ) {
    throwUnlessClientIsAvailable(promise) {
      coroutineScope.launch {
        val record = reactRecordTypeToClassMap[recordType]
        if (record != null) {
          healthConnectClient.deleteRecords(
            recordType = record, timeRangeFilter = timeRangeFilter.getTimeRangeFilter()
          )
        }
      }
    }
  }

      fun registerForChangeTokens(promise: Promise) {
        throwUnlessClientIsAvailable(promise) {
            coroutineScope.launch {
                try {
                    // Register for changes and handle token
                    val changesToken = healthConnectClient.getChangesToken(
                            ChangesTokenRequest(recordTypes = setOf(
                              WeightRecord::class, 
                              StepsRecord::class, 
                              BodyFatRecord::class, 
                              SleepSessionRecord::class, 
                              LeanBodyMassRecord::class
                          ))
                    )
                    promise.resolve(changesToken)
                } catch (e: Exception) {
                    promise.rejectWithException(e)
                }
            }
        }
    }

    fun getChangeToken(promise: Promise) {
        throwUnlessClientIsAvailable(promise) {
            coroutineScope.launch {
                try {
                    val changesToken = healthConnectClient.getChangesToken(
                            ChangesTokenRequest(recordTypes = setOf(
                              WeightRecord::class, 
                              StepsRecord::class, 
                              BodyFatRecord::class, 
                              SleepSessionRecord::class, 
                              LeanBodyMassRecord::class
                          ))
                    )
                    promise.resolve(changesToken)
                } catch (e: Exception) {
                    promise.rejectWithException(e)
                }
            }
        }
    }


      suspend fun processChangesWithRetry(
    healthConnectClient: HealthConnectClient,
    token: String,
    maxRetries: Int = 5,
    initialDelay: Long = 1000L
): ChangesResponse? {
    var currentToken = token
    var currentRetry = 0
    var delayTime = initialDelay

    while (currentRetry < maxRetries) {
        try {
            val response = healthConnectClient.getChanges(currentToken)
            return response
        } catch (e: Exception) {
            if (isRateLimitError(e)) {
                currentRetry++
                delay(delayTime)
                delayTime *= 2 // Exponential backoff
            } else {
                throw e
            }
        }
    }

    throw Exception("Max retries exceeded")
}

fun isRateLimitError(e: Exception): Boolean {
    // Check if the exception indicates a rate limit error
    // This might involve checking the exception message or type
    return e.message?.contains("rate limit") ?: false
}

// Updated processChanges method
fun processChanges(token: String, promise: Promise) {
    throwUnlessClientIsAvailable(promise) {
        coroutineScope.launch {
            try {
                var nextChangesToken = token
                do {
                    val response = processChangesWithRetry(healthConnectClient, nextChangesToken)
                    val upsertionArray = Arguments.createArray()
                    val deletionArray = Arguments.createArray()

                    response?.changes?.forEach { change ->
                        when (change) {
                            is UpsertionChange -> {
                                if (change.record.metadata.dataOrigin.packageName != applicationContext.packageName) {
                                    processUpsertionChange(change, upsertionArray)
                                }
                            }
                            is DeletionChange -> processDeletionChange(change, deletionArray)
                        }
                    }
                    nextChangesToken = response?.nextChangesToken ?: nextChangesToken

                    val result = Arguments.createMap()
                    result.putArray("upsertions", upsertionArray)
                    result.putArray("deletions", deletionArray)
                    result.putString("nextChangesToken", nextChangesToken)
                    promise.resolve(result)
                } while (response?.hasMore == true)
            } catch (e: Exception) {
                promise.rejectWithException(e)
            }
        }
    }
}


private fun processUpsertionChange(change: UpsertionChange, upsertionArray: WritableArray) {
    val recordMap = Arguments.createMap()
    recordMap.putString("id", change.record.metadata.id)
    recordMap.putString("lastModifiedTime", change.record.metadata.lastModifiedTime.toString())
    recordMap.putString("recordingMethod", change.record.metadata.recordingMethod.toString())

        when (change.record) {
            is WeightRecord -> {
                val weightRecord = change.record as WeightRecord
                recordMap.putString("recordType", "WeightRecord")
                recordMap.putDouble("weight", weightRecord.weight.inKilograms)
                recordMap.putString("time", weightRecord.time.toString())
                recordMap.putString("zoneOffset", weightRecord.zoneOffset.toString())
              }
              is StepsRecord -> {
                val stepsRecord = change.record as StepsRecord
                recordMap.putString("recordType", "StepsRecord")
                recordMap.putInt("count", stepsRecord.count.toInt())
                recordMap.putString("startTime", stepsRecord.startTime.toString())
                recordMap.putString("endTime", stepsRecord.endTime.toString())
              }
              is BodyFatRecord -> {
                  val bodyFatRecord = change.record as BodyFatRecord
                  recordMap.putString("recordType", "BodyFatRecord")
                  recordMap.putDouble("percentage", bodyFatRecord.percentage.value)
                  recordMap.putString("time", bodyFatRecord.time.toString())
                  recordMap.putString("zoneOffset", bodyFatRecord.zoneOffset.toString())
              }
              is SleepSessionRecord -> {
                  val sleepSessionRecord = change.record as SleepSessionRecord
                  recordMap.putString("recordType", "SleepSessionRecord")
                  recordMap.putString("startTime", sleepSessionRecord.startTime.toString())
                  recordMap.putString("endTime", sleepSessionRecord.endTime.toString())
                  recordMap.putString("title", sleepSessionRecord.title ?: "")
                  recordMap.putString("notes", sleepSessionRecord.notes ?: "")
                  recordMap.putDouble("duration", (sleepSessionRecord.endTime.toEpochMilli() - sleepSessionRecord.startTime.toEpochMilli()).toDouble() / 1000) // Duration in seconds
                  val stagesArray = Arguments.createArray()
                  sleepSessionRecord.stages.forEach { stage ->
                      val stageMap = Arguments.createMap()
                      stageMap.putString("stage", stage.stage.toString())
                      stageMap.putString("startTime", stage.startTime.toString())
                      stageMap.putString("endTime", stage.endTime.toString())
                      val stageDuration = (stage.endTime.toEpochMilli() - stage.startTime.toEpochMilli()).toDouble() / 1000 // Duration in seconds
                      stageMap.putDouble("duration", stageDuration)
                      stagesArray.pushMap(stageMap)
                  }
                  recordMap.putArray("stages", stagesArray)
              }
              is LeanBodyMassRecord -> {
                  val leanBodyMassRecord = change.record as LeanBodyMassRecord
                  recordMap.putString("recordType", "LeanBodyMassRecord")
                  recordMap.putDouble("mass", leanBodyMassRecord.mass.inKilograms)
                  recordMap.putString("time", leanBodyMassRecord.time.toString())
                  recordMap.putString("zoneOffset", leanBodyMassRecord.zoneOffset.toString())
              }
            else -> {
                recordMap.putString("recordType", change.record::class.simpleName)
            }
        }

    upsertionArray.pushMap(recordMap)
}

    private fun processDeletionChange(change: DeletionChange, deletionArray: WritableArray) {
        val recordMap = Arguments.createMap()
        recordMap.putString("id", change.recordId)
        // Add other fields from change if needed
        deletionArray.pushMap(recordMap)
    }
}


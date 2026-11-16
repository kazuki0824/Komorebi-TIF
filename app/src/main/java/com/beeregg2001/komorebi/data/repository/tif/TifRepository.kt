package com.beeregg2001.komorebi.data.repository.tif

import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.media.tv.TvContract
import android.media.tv.TvInputInfo
import android.media.tv.TvInputManager
import android.net.Uri
import com.beeregg2001.komorebi.data.model.*
import com.beeregg2001.komorebi.data.repository.*
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import javax.inject.Inject
import javax.inject.Singleton

/** Maleicacid TISの公開TvInputInfoだけを見て実際のinputIdを解決する。 */
object TifInput {
    const val BACKEND_TYPE = "TIF"
    const val READ_LISTINGS_PERMISSION = "android.permission.READ_TV_LISTINGS"
    private const val PACKAGE_NAME = "com.maleicacid.tvinput"
    private const val SERVICE_NAME = "com.maleicacid.tvinput.tis.MaleicacidTvInputService"
    private const val CHANNEL_PREFIX = "tif:"

    fun findInput(context: Context): TvInputInfo? {
        val manager = context.getSystemService(TvInputManager::class.java) ?: return null
        return manager.tvInputList.filter {
            it.serviceInfo.packageName == PACKAGE_NAME && it.serviceInfo.name == SERVICE_NAME
        }.singleOrNull()
    }

    fun channelId(rowId: Long): String = CHANNEL_PREFIX + rowId

    fun channelUri(id: String): Uri? {
        if (!id.startsWith(CHANNEL_PREFIX)) return null
        val rowId = id.removePrefix(CHANNEL_PREFIX).toLongOrNull()?.takeIf { it > 0 } ?: return null
        return TvContract.buildChannelUri(rowId)
    }
}

/**
 * 通常APKからREAD_TV_LISTINGSで読める公開channel/programだけを参照する。
 * internal_provider_dataやTuner HALを参照しない。録画はtv#116のcanRecord=falseに従う。
 */
@Singleton
class TifRepository @Inject constructor(
    @ApplicationContext private val context: Context
) : LiveProvider, EpgProvider, RecordProvider, ReserveProvider {
    private data class ChannelRow(
        val id: Long, val number: String, val name: String,
        val onid: Long, val tsid: Long, val sid: Long, val type: String
    )
    private data class ProgramRow(
        val id: Long, val eventId: Int, val start: Long, val end: Long,
        val title: String, val description: String, val isFree: Boolean
    )

    private fun requireListingsPermission() {
        if (context.checkSelfPermission(TifInput.READ_LISTINGS_PERMISSION) != PackageManager.PERMISSION_GRANTED) {
            throw SecurityException("TV番組一覧へのアクセス許可がありません。アプリの権限設定を確認してください。")
        }
    }

    private fun Cursor.value(index: Int): String = if (isNull(index)) "" else getString(index).orEmpty()
    private fun Cursor.number(index: Int): Long = if (isNull(index)) 0L else getLong(index)

    private fun readChannels(): List<ChannelRow> {
        requireListingsPermission()
        val inputId = TifInput.findInput(context)?.id
            ?: error("Maleicacid TV入力がありません。tv#116のTISを製品へ組み込んでください。")
        val projection = arrayOf(
            TvContract.Channels._ID,
            TvContract.Channels.COLUMN_DISPLAY_NUMBER,
            TvContract.Channels.COLUMN_DISPLAY_NAME,
            TvContract.Channels.COLUMN_ORIGINAL_NETWORK_ID,
            TvContract.Channels.COLUMN_TRANSPORT_STREAM_ID,
            TvContract.Channels.COLUMN_SERVICE_ID,
            TvContract.Channels.COLUMN_TYPE,
            TvContract.Channels.COLUMN_BROWSABLE,
            TvContract.Channels.COLUMN_SEARCHABLE
        )
        val cursor = context.contentResolver.query(
            TvContract.buildChannelsUriForInput(inputId), projection, null, null, null
        ) ?: error("TvProviderのchannel queryがnullを返しました。")
        return cursor.use {
            val rows = mutableListOf<ChannelRow>()
            while (it.moveToNext()) {
                // 初期非表示・ワンセグを勝手に公開しない。
                if (it.number(7) != 1L || it.number(8) != 1L) continue
                val onid = it.number(3)
                val type = when (it.value(6)) {
                    TvContract.Channels.TYPE_1SEG -> continue
                    TvContract.Channels.TYPE_ISDB_T -> "GR"
                    TvContract.Channels.TYPE_ISDB_S -> if (onid == 6L || onid == 7L) "CS" else "BS"
                    else -> continue
                }
                rows += ChannelRow(
                    id = it.getLong(0), number = it.value(1), name = it.value(2),
                    onid = onid, tsid = it.number(4), sid = it.number(5), type = type
                )
            }
            rows.sortedWith(compareBy({ listOf("GR", "BS", "CS").indexOf(it.type) }, { it.number }))
        }
    }

    private fun readPrograms(channelId: Long, start: Long, end: Long): List<ProgramRow> {
        val projection = arrayOf(
            TvContract.Programs._ID,
            TvContract.Programs.COLUMN_EVENT_ID,
            TvContract.Programs.COLUMN_START_TIME_UTC_MILLIS,
            TvContract.Programs.COLUMN_END_TIME_UTC_MILLIS,
            TvContract.Programs.COLUMN_TITLE,
            TvContract.Programs.COLUMN_SHORT_DESCRIPTION,
            TvContract.Programs.COLUMN_SCRAMBLED
        )
        val cursor = context.contentResolver.query(
            TvContract.buildProgramsUriForChannel(channelId, start, end),
            projection, null, null, null
        ) ?: error("TvProviderのprogram queryがnullを返しました。")
        return cursor.use {
            val rows = mutableListOf<ProgramRow>()
            while (it.moveToNext()) {
                val from = it.number(2)
                val to = it.number(3)
                if (from <= 0 || to <= from) continue
                rows += ProgramRow(
                    id = it.getLong(0), eventId = it.number(1).toInt(),
                    start = from, end = to, title = it.value(4),
                    description = it.value(5),
                    isFree = !it.isNull(6) && it.getInt(6) == 0
                )
            }
            rows.sortedBy(ProgramRow::start)
        }
    }

    private fun utc(ms: Long): String =
        OffsetDateTime.ofInstant(Instant.ofEpochMilli(ms), ZoneOffset.UTC).toString()

    private fun seconds(from: Long, end: Long): Int =
        ((end - from) / 1000).coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()

    private fun ProgramRow.toLive(): Program = Program(
        id = "tif-program:$id", title = title, description = description, detail = null,
        startTime = utc(start), endTime = utc(end),
        duration = seconds(start, end), genres = null, videoResolution = null
    )

    private fun ChannelRow.toChannel(present: ProgramRow?, following: ProgramRow?): Channel = Channel(
        id = TifInput.channelId(id),
        displayChannelId = number.ifBlank { sid.toString() },
        name = name.ifBlank { "サービス $sid" },
        channelNumber = number.ifBlank { sid.toString() },
        networkId = onid, serviceId = sid, transportStreamId = tsid, type = type,
        isWatchable = true, is_subchannel = false, isDisplay = true,
        programPresent = present?.toLive(), programFollowing = following?.toLive(), remocon_Id = 0
    )

    override suspend fun getChannels(): ChannelApiResponse = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val channels = readChannels().map { channel ->
            val programs = readPrograms(channel.id, now - 60_000L, now + 6 * 60 * 60_000L)
            channel.toChannel(
                programs.firstOrNull { it.start <= now && it.end > now },
                programs.firstOrNull { it.start > now }
            )
        }
        ChannelApiResponse(
            terrestrial = channels.filter { it.type == "GR" },
            bs = channels.filter { it.type == "BS" },
            cs = channels.filter { it.type == "CS" }
        )
    }

    // TvView.tune()に渡すchannel URIはストリームURLとは異なる。
    override suspend fun getLiveStreamUrl(channelId: String, quality: String, streamNumber: Int): String =
        throw UnsupportedOperationException("TIFはTvView.tune()で視聴します。")

    override suspend fun getChannelLogoUrl(channelId: String): String = ""

    override suspend fun getEpgPrograms(
        startTime: String?, endTime: String?, channelType: String?
    ): List<EpgChannelWrapper> = withContext(Dispatchers.IO) {
        val start = startTime?.let { OffsetDateTime.parse(it).toInstant().toEpochMilli() }
            ?: (System.currentTimeMillis() - 60 * 60_000L)
        val end = endTime?.let { OffsetDateTime.parse(it).toInstant().toEpochMilli() }
            ?: (start + 24 * 60 * 60_000L)
        if (end <= start) return@withContext emptyList()
        readChannels().filter { channelType.isNullOrBlank() || it.type == channelType }.map { row ->
            val channelId = TifInput.channelId(row.id)
            EpgChannelWrapper(
                channel = EpgChannel(
                    id = channelId, display_channel_id = row.number.ifBlank { row.sid.toString() },
                    network_id = row.onid.toInt(), service_id = row.sid.toInt(),
                    transport_stream_id = row.tsid.toInt(), remocon_id = 0,
                    channel_number = row.number.ifBlank { row.sid.toString() },
                    type = row.type, name = row.name, jikkyo_force = null,
                    is_subchannel = false, is_radiochannel = false, is_watchable = true
                ),
                programs = readPrograms(row.id, start, end).map { program ->
                    EpgProgram(
                        id = "tif-program:" + program.id, channel_id = channelId,
                        network_id = row.onid.toInt(), service_id = row.sid.toInt(),
                        event_id = program.eventId,
                        title = program.title, description = program.description, detail = null,
                        start_time = utc(program.start), end_time = utc(program.end),
                        duration = seconds(program.start, program.end), is_free = program.isFree,
                        genres = null, video_type = null, audio_type = null, audio_sampling_rate = null
                    )
                }
            )
        }
    }

    override suspend fun getPinnedEpgPrograms(pinnedChannelIds: String): List<EpgChannelWrapper> {
        val wanted = pinnedChannelIds.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        if (wanted.isEmpty()) return emptyList()
        return getEpgPrograms(null, null, null).filter { it.channel.id in wanted }
    }

    private fun unsupported() = UnsupportedOperationException("tv#116のTISは録画・予約に非対応です。")

    override suspend fun getRecordedPrograms(page: Int) = RecordedApiResponse(0, emptyList())
    override suspend fun getRecordedProgram(videoId: Int): Result<RecordedProgram> = Result.failure(unsupported())
    override suspend fun searchRecordedPrograms(keyword: String, page: Int) =
        RecordedApiResponse(0, emptyList())
    override suspend fun getRecordStreamUrl(
        videoId: Int, quality: String, sessionId: String, offsetSeconds: Double
    ): String = throw unsupported()
    override suspend fun getArchivedJikkyo(videoId: Int): Result<List<ArchivedComment>> =
        Result.failure(unsupported())
    override suspend fun keepAlive(videoId: Int, quality: String, sessionId: String) { throw unsupported() }
    override suspend fun getTiledThumbnailUrl(videoId: Int): String? = null

    override suspend fun getReserves(): Result<List<ReserveItem>> = Result.success(emptyList())
    override suspend fun addReserve(request: ReserveRequest): Result<Unit> = Result.failure(unsupported())
    override suspend fun updateReserve(reservationId: Int, request: ReserveRequest): Result<Unit> =
        Result.failure(unsupported())
    override suspend fun deleteReservation(reservationId: Int): Result<Unit> = Result.failure(unsupported())
    override suspend fun getReservationConditions(): Result<List<ReservationCondition>> =
        Result.success(emptyList())
    override suspend fun addReservationCondition(request: ReservationConditionAddRequest): Result<Unit> =
        Result.failure(unsupported())
    override suspend fun updateReservationCondition(
        conditionId: Int, request: ReservationConditionUpdateRequest
    ): Result<ReservationCondition> = Result.failure(unsupported())
    override suspend fun deleteReservationCondition(conditionId: Int): Result<Unit> =
        Result.failure(unsupported())
}

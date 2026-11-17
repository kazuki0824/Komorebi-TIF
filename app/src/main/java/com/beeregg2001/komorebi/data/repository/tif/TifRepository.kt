package com.beeregg2001.komorebi.data.repository.tif

import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.media.tv.TvContract
import android.media.tv.TvInputInfo
import android.media.tv.TvInputManager
import android.net.Uri
import android.os.Build
import android.util.Log
import com.beeregg2001.komorebi.data.model.*
import com.beeregg2001.komorebi.data.repository.*
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 特定のTV入力提供元を指定せず、frameworkの公開TvInputInfoから選局対象を解決する。
 * TvProviderのrow IDだけでなく、所有input IDもチャンネル識別子へ保持する。
 */
object TifInput {
    const val BACKEND_TYPE = "TIF"
    const val READ_LISTINGS_PERMISSION = "android.permission.READ_TV_LISTINGS"
    private const val CHANNEL_PREFIX = "tif:channel:"
    private const val PASSTHROUGH_PREFIX = "tif:input:"

    data class TuneTarget(val inputId: String, val channelUri: Uri)

    fun inputs(context: Context): List<TvInputInfo> {
        val manager = context.getSystemService(TvInputManager::class.java) ?: return emptyList()
        return manager.tvInputList.filterNot { it.isHidden(context) }
            .sortedBy { it.loadCustomLabel(context)?.toString() ?: it.loadLabel(context)?.toString() ?: it.id }
    }

    fun channelId(inputId: String, rowId: Long): String =
        CHANNEL_PREFIX + rowId + ":" + Uri.encode(inputId)

    fun passthroughId(inputId: String): String =
        PASSTHROUGH_PREFIX + Uri.encode(inputId)

    fun resolveTarget(context: Context, id: String): TuneTarget? {
        val inputInfos = inputs(context).associateBy { it.id }
        if (id.startsWith(PASSTHROUGH_PREFIX)) {
            val inputId = Uri.decode(id.removePrefix(PASSTHROUGH_PREFIX))
            val info = inputInfos[inputId] ?: return null
            if (!info.isPassthroughInput) return null
            return TuneTarget(info.id, TvContract.buildChannelUriForPassthroughInput(info.id))
        }

        if (!id.startsWith(CHANNEL_PREFIX)) return null
        val suffix = id.removePrefix(CHANNEL_PREFIX)
        val splitIndex = suffix.indexOf(':')
        if (splitIndex <= 0) return null
        val rowId = suffix.substring(0, splitIndex).toLongOrNull()?.takeIf { it > 0 } ?: return null
        val inputId = Uri.decode(suffix.substring(splitIndex + 1))
        val info = inputInfos[inputId] ?: return null
        if (info.isPassthroughInput) return null
        // 再スキャンによるrow ID再利用などで、保存済みIDが別の入力へ乗り移らないようにする。
        val cursor = context.contentResolver.query(
            TvContract.buildChannelUri(rowId),
            arrayOf(
                TvContract.Channels.COLUMN_INPUT_ID,
                TvContract.Channels.COLUMN_BROWSABLE,
                TvContract.Channels.COLUMN_SEARCHABLE
            ),
            null, null, null
        ) ?: return null
        val stillOwned = cursor.use { rows ->
            rows.moveToFirst() && rows.getString(0) == info.id &&
                rows.getInt(1) == 1 && rows.getInt(2) == 1
        }
        return if (stillOwned) TuneTarget(info.id, TvContract.buildChannelUri(rowId)) else null
    }
}

/**
 * Android TVの公開TvProviderとTvInputManagerだけを利用するTIFアダプター。
 * メーカー固有のinternal_provider_data、Tuner SDK、HAL、CASの所有には入らない。
 */
@Singleton
class TifRepository @Inject constructor(
    @ApplicationContext private val context: Context
) : LiveProvider, EpgProvider, RecordProvider, ReserveProvider {
    private data class ChannelRow(
        val id: Long, val inputId: String, val number: String, val name: String,
        val onid: Long, val tsid: Long, val sid: Long, val category: String,
        val audioOnly: Boolean
    )

    private data class ProgramRow(
        val id: Long, val eventId: Int, val start: Long, val end: Long,
        val title: String, val description: String, val isFree: Boolean
    )

    private fun hasListingsPermission(): Boolean =
        context.checkSelfPermission(TifInput.READ_LISTINGS_PERMISSION) == PackageManager.PERMISSION_GRANTED

    private fun Cursor.stringAt(index: Int): String =
        if (isNull(index)) "" else getString(index).orEmpty()

    private fun Cursor.longAt(index: Int): Long =
        if (isNull(index)) 0L else getLong(index)

    private fun category(channelType: String, onid: Long): String = when (channelType) {
        TvContract.Channels.TYPE_ISDB_T, TvContract.Channels.TYPE_1SEG -> "GR"
        TvContract.Channels.TYPE_ISDB_S -> if (onid == 6L || onid == 7L) "CS" else "BS"
        else -> "TV" // ATSC/DVB/IPTVなどは放送方式を推測せずその他のTV入力として表示する
    }

    private fun readTunerChannels(inputs: List<TvInputInfo>): List<ChannelRow> {
        val available = inputs.filterNot { it.isPassthroughInput }.map { it.id }.toSet()
        if (available.isEmpty()) return emptyList()
        check(hasListingsPermission()) { "チューナー入力の視聴一覧を取得するにはREAD_TV_LISTINGSの許可が必要です。" }

        val projection = arrayOf(
            TvContract.Channels._ID,
            TvContract.Channels.COLUMN_INPUT_ID,
            TvContract.Channels.COLUMN_DISPLAY_NUMBER,
            TvContract.Channels.COLUMN_DISPLAY_NAME,
            TvContract.Channels.COLUMN_ORIGINAL_NETWORK_ID,
            TvContract.Channels.COLUMN_TRANSPORT_STREAM_ID,
            TvContract.Channels.COLUMN_SERVICE_ID,
            TvContract.Channels.COLUMN_TYPE,
            TvContract.Channels.COLUMN_BROWSABLE,
            TvContract.Channels.COLUMN_SEARCHABLE,
            TvContract.Channels.COLUMN_SERVICE_TYPE
        )
        val cursor = context.contentResolver.query(
            TvContract.Channels.CONTENT_URI, projection, null, null, null
        ) ?: error("TvProviderがchannel queryにnullを返しました。")
        return cursor.use {
            val out = mutableListOf<ChannelRow>()
            while (it.moveToNext()) {
                val ownerInput = it.stringAt(1)
                if (ownerInput !in available) continue
                // 一覧公開の判断は各TIS/TvProviderに委ねる。ワンセグ等の独自除外はしない。
                if (it.longAt(8) != 1L || it.longAt(9) != 1L) continue
                val rowId = it.longAt(0)
                if (rowId <= 0) continue
                val onid = it.longAt(4)
                out += ChannelRow(
                    id = rowId, inputId = ownerInput,
                    number = it.stringAt(2), name = it.stringAt(3),
                    onid = onid, tsid = it.longAt(5), sid = it.longAt(6),
                    category = category(it.stringAt(7), onid),
                    audioOnly = it.stringAt(10) == TvContract.Channels.SERVICE_TYPE_AUDIO
                )
            }
            out.sortedWith(compareBy<ChannelRow>({ it.category }, { it.number }, { it.inputId }, { it.id }))
        }
    }

    private fun readPrograms(rowId: Long, start: Long, end: Long): List<ProgramRow> {
        // optional列の導入APIを守り、古いTvProviderへ未知の列を要求しない。
        val columns = mutableListOf(
            TvContract.Programs._ID,
            TvContract.Programs.COLUMN_START_TIME_UTC_MILLIS,
            TvContract.Programs.COLUMN_END_TIME_UTC_MILLIS,
            TvContract.Programs.COLUMN_TITLE
        )
        val descriptionIndex = if (Build.VERSION.SDK_INT >= 26) {
            columns.add(TvContract.Programs.COLUMN_SHORT_DESCRIPTION)
            columns.lastIndex
        } else -1
        val eventIdIndex = if (Build.VERSION.SDK_INT >= 30) {
            columns.add(TvContract.Programs.COLUMN_EVENT_ID)
            columns.lastIndex
        } else -1
        val scrambledIndex = if (Build.VERSION.SDK_INT >= 33) {
            columns.add(TvContract.Programs.COLUMN_SCRAMBLED)
            columns.lastIndex
        } else -1

        val cursor = context.contentResolver.query(
            TvContract.buildProgramsUriForChannel(rowId, start, end),
            columns.toTypedArray(), null, null, null
        ) ?: error("TvProviderがprogram queryにnullを返しました。")
        return cursor.use {
            val result = mutableListOf<ProgramRow>()
            while (it.moveToNext()) {
                val from = it.longAt(1)
                val to = it.longAt(2)
                if (from <= 0 || to <= from) continue
                result += ProgramRow(
                    id = it.longAt(0),
                    eventId = if (eventIdIndex >= 0) it.longAt(eventIdIndex).toInt() else 0,
                    start = from, end = to, title = it.stringAt(3),
                    description = if (descriptionIndex >= 0) it.stringAt(descriptionIndex) else "",
                    // scrambling有無を「有料/無料」と同一視しない。未提供時は真偽を推測しない。
                    isFree = scrambledIndex >= 0 && !it.isNull(scrambledIndex) &&
                        it.getInt(scrambledIndex) == 0
                )
            }
            result.sortedBy { it.start }
        }
    }

    // TvProvider epochはUTC基準の瞬間。既存UIはOffsetDateTimeをゾーン変換せず表示・
    // 放送日04:00境界で評価するため、モデル境界で端末の表示ゾーンへ変換する。
    private fun displayTime(ms: Long): String =
        OffsetDateTime.ofInstant(Instant.ofEpochMilli(ms), ZoneId.systemDefault()).toString()

    private fun seconds(start: Long, end: Long): Int =
        ((end - start) / 1000).coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()

    private fun ProgramRow.toLive(): Program = Program(
        id = "tif-program:" + id, title = title, description = description,
        detail = null, startTime = displayTime(start), endTime = displayTime(end),
        duration = seconds(start, end), genres = null, videoResolution = null
    )

    private fun ChannelRow.toLiveChannel(current: ProgramRow?, next: ProgramRow?): Channel = Channel(
        id = TifInput.channelId(inputId, id),
        displayChannelId = number.ifBlank { sid.toString() },
        name = name.ifBlank { "TVチャンネル " + id },
        channelNumber = number.ifBlank { sid.toString() },
        networkId = onid, serviceId = sid, transportStreamId = tsid,
        type = category, isWatchable = true, is_subchannel = false,
        isDisplay = true, programPresent = current?.toLive(),
        programFollowing = next?.toLive(), remocon_Id = 0
    )

    private fun TvInputInfo.toPassthroughChannel(): Channel {
        val label = loadCustomLabel(context)?.toString()
            ?: loadLabel(context)?.toString() ?: id
        return Channel(
            id = TifInput.passthroughId(id),
            displayChannelId = label, name = label, channelNumber = label,
            networkId = 0L, serviceId = 0L, transportStreamId = 0L,
            type = "INPUT", isWatchable = true, is_subchannel = false,
            isDisplay = true, programPresent = null, programFollowing = null, remocon_Id = 0
        )
    }

    override suspend fun getChannels(): ChannelApiResponse = withContext(Dispatchers.IO) {
        val infos = TifInput.inputs(context)
        if (infos.isEmpty()) error("利用可能なTV入力がありません。AndroidのTV入力設定を確認してください。")
        val passthrough = infos.filter { it.isPassthroughInput }.map { it.toPassthroughChannel() }
        val tunerRows = if (hasListingsPermission()) {
            readTunerChannels(infos)
        } else {
            if (infos.any { !it.isPassthroughInput } && passthrough.isEmpty()) {
                error("TV番組一覧を読むにはREAD_TV_LISTINGSの許可が必要です。")
            }
            if (infos.any { !it.isPassthroughInput }) {
                Log.w("TifRepository", "READ_TV_LISTINGS未許可のためチューナー一覧を省略します")
            }
            emptyList()
        }
        val now = System.currentTimeMillis()
        val channels = tunerRows.map { row ->
            // 別TISがEPGを公開していなくても視聴チャンネルまで消さない。
            val programs = try {
                readPrograms(row.id, now - 60_000L, now + 6 * 60 * 60_000L)
            } catch (error: SecurityException) {
                throw error // 権限問題は明示する
            } catch (error: Exception) {
                Log.w("TifRepository", "番組表取得失敗 rowId=" + row.id, error)
                emptyList()
            }
            row.toLiveChannel(
                programs.firstOrNull { it.start <= now && it.end > now },
                programs.firstOrNull { it.start > now }
            )
        }
        ChannelApiResponse(
            terrestrial = channels.filter { it.type == "GR" },
            bs = channels.filter { it.type == "BS" },
            cs = channels.filter { it.type == "CS" },
            others = channels.filter { it.type == "TV" },
            inputs = passthrough
        )
    }

    // TIFのchannel URIはMedia3が受け取るストリームURLではない。
    override suspend fun getLiveStreamUrl(channelId: String, quality: String, streamNumber: Int): String =
        throw UnsupportedOperationException("TIFはTvView.tune()によって視聴します。")
    override suspend fun getChannelLogoUrl(channelId: String): String = ""

    override suspend fun getEpgPrograms(
        startTime: String?, endTime: String?, channelType: String?
    ): List<EpgChannelWrapper> = withContext(Dispatchers.IO) {
        val from = startTime?.let { OffsetDateTime.parse(it).toInstant().toEpochMilli() }
            ?: (System.currentTimeMillis() - 60 * 60_000L)
        val to = endTime?.let { OffsetDateTime.parse(it).toInstant().toEpochMilli() }
            ?: (from + 24 * 60 * 60_000L)
        if (to <= from || channelType == "INPUT") return@withContext emptyList()
        val infos = TifInput.inputs(context)
        val rows = readTunerChannels(infos)
        rows.filter { channelType.isNullOrBlank() || it.category == channelType }.map { row ->
            val id = TifInput.channelId(row.inputId, row.id)
            val programs = try {
                readPrograms(row.id, from, to)
            } catch (error: SecurityException) {
                throw error
            } catch (error: Exception) {
                Log.w("TifRepository", "EPG取得失敗 rowId=" + row.id, error)
                emptyList()
            }
            EpgChannelWrapper(
                channel = EpgChannel(
                    id = id, display_channel_id = row.number.ifBlank { row.sid.toString() },
                    network_id = row.onid.toInt(), service_id = row.sid.toInt(),
                    transport_stream_id = row.tsid.toInt(), remocon_id = 0,
                    channel_number = row.number.ifBlank { row.sid.toString() },
                    type = row.category, name = row.name, jikkyo_force = null,
                    is_subchannel = false, is_radiochannel = row.audioOnly, is_watchable = true
                ),
                programs = programs.map { program ->
                    EpgProgram(
                        id = "tif-program:" + program.id, channel_id = id,
                        network_id = row.onid.toInt(), service_id = row.sid.toInt(),
                        event_id = program.eventId, title = program.title,
                        description = program.description, detail = null,
                        start_time = displayTime(program.start), end_time = displayTime(program.end),
                        duration = seconds(program.start, program.end), is_free = program.isFree,
                        genres = null, video_type = null, audio_type = null, audio_sampling_rate = null
                    )
                }
            )
        }
    }

    override suspend fun getPinnedEpgPrograms(pinnedChannelIds: String): List<EpgChannelWrapper> {
        val wanted = pinnedChannelIds.split(",").map { it.trim() }.filter { it.isNotBlank() }.toSet()
        if (wanted.isEmpty()) return emptyList()
        return getEpgPrograms(null, null, null).filter { it.channel.id in wanted }
    }

    // 汎用TIFの録画対応可否と、Komorebiの録画UI実装可否は別問題。
    // このAdapterは視聴専用であり、他のネットワークバックエンドへ誤転送しない。
    private fun unsupported() = UnsupportedOperationException("TIFでの録画・予約はKomorebi側で未実装です。")

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

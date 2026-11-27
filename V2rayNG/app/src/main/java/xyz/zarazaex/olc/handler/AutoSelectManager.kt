package xyz.zarazaex.olc.handler

import xyz.zarazaex.olc.dto.ProfileItem
import xyz.zarazaex.olc.enums.EConfigType
import xyz.zarazaex.olc.util.JsonUtil
import xyz.zarazaex.olc.util.Utils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * Закреплённый профиль «Автовыбор» в подписках «Белые списки» и «Черные списки»
 * (см. DefaultSubscriptions.AUTO_SELECT_NAMES).
 *
 * «Автовыбор» — маркерный профиль (POLICYGROUP с фиксированным remarks). При запуске он
 * превращается в обычный конфиг ОДНОГО сервера (см. currentTarget / resolveGuid).
 * Остальные серверы подписки проверяются только тогда, когда текущий перестал отвечать
 * (см. pickBest и сторож в V2RayServiceManager).
 * Серверы из России в выборку не попадают (см. isRussian).
 *
 * Состояние (текущий сервер) хранится отдельно для каждой подписки.
 */
object AutoSelectManager {

    const val REMARKS = "⚡ Автовыбор"

    private const val KEY_CURRENT = "auto_select_current_guid"
    private const val KEY_CURRENT_ID = "auto_select_current_id"

    /** Сколько серверов проверяется TCP-пингом одновременно. */
    private const val TCP_PARALLELISM = 20

    /** Сколько лучших по пингу кандидатов возвращает pickBest. */
    private const val TOP_N = 5

    private val RU_REGEX = Regex("""🇷🇺|\bRU\b|russia|росси""", RegexOption.IGNORE_CASE)

    fun isAutoProfile(profile: ProfileItem?): Boolean {
        return profile != null
                && profile.configType == EConfigType.POLICYGROUP
                && profile.remarks == REMARKS
    }

    /** Относится ли подписка к тем, где нужен «Автовыбор» (белые и чёрные списки). */
    fun isAutoSubscription(subid: String): Boolean {
        if (subid.isBlank()) return false
        val sub = MmkvManager.decodeSubscription(subid) ?: return false
        return sub.remarks in DefaultSubscriptions.AUTO_SELECT_NAMES
    }

    /**
     * Сервер считается российским, если об этом говорит название (флаг, RU, Russia, Россия)
     * или если страна уже определена в кэше стран (см. CountryDetector).
     */
    fun isRussian(profile: ProfileItem): Boolean {
        if (RU_REGEX.containsMatchIn(profile.remarks)) return true
        return CountryDetector.getCountryCodes(profile.remarks, profile.server)
            .any { it.equals("RU", ignoreCase = true) }
    }

    // region профиль

    /**
     * Создаёт профиль «Автовыбор» (если его нет) и ставит его первым в списке подписки.
     * Профиль помечается избранным, поэтому при любой сортировке (по пингу и т.д.)
     * остаётся наверху списка.
     * Работает только для подписок из DefaultSubscriptions.AUTO_SELECT_NAMES.
     *
     * @return GUID профиля «Автовыбор» или null, если подписка не подходит.
     */
    fun ensureProfile(subid: String): String? {
        if (!isAutoSubscription(subid)) return null

        val list = MmkvManager.decodeServerList(subid)
        val existing = list.firstOrNull { guid ->
            isAutoProfile(MmkvManager.decodeServerConfig(guid))
        }

        if (existing != null) {
            val profile = MmkvManager.decodeServerConfig(existing)
            if (profile != null && !profile.isFavorite) {
                profile.isFavorite = true
                MmkvManager.encodeProfileDirect(existing, JsonUtil.toJson(profile))
            }
            list.remove(existing)
            list.add(0, existing)
            MmkvManager.encodeServerList(list, subid)
            return existing
        }

        val profile = ProfileItem.create(EConfigType.POLICYGROUP).apply {
            remarks = REMARKS
            description = "Самый быстрый сервер, без России"
            subscriptionId = subid
            policyGroupSubscriptionId = subid
            isFavorite = true
        }
        val key = Utils.getUuid()
        MmkvManager.encodeProfileDirect(key, JsonUtil.toJson(profile))
        list.add(0, key)
        MmkvManager.encodeServerList(list, subid)
        return key
    }

    // endregion

    // region выбор сервера

    /**
     * Кандидаты автовыбора: серверы подписки без России, только TCP-протоколы
     * (UDP-протоколы нельзя проверить TCP-пингом).
     */
    fun candidates(subid: String): List<Pair<String, ProfileItem>> {
        return MmkvManager.decodeServerList(subid).mapNotNull { guid ->
            val p = MmkvManager.decodeServerConfig(guid) ?: return@mapNotNull null
            when {
                p.configType == EConfigType.CUSTOM
                        || p.configType == EConfigType.POLICYGROUP
                        || p.configType == EConfigType.HYSTERIA2
                        || p.configType == EConfigType.WIREGUARD -> null

                p.server.isNullOrBlank() || p.serverPort?.toIntOrNull() == null -> null
                isRussian(p) -> null
                else -> guid to p
            }
        }
    }

    private fun guidKey(subid: String) = "$KEY_CURRENT:$subid"

    private fun idKey(subid: String) = "$KEY_CURRENT_ID:$subid"

    /** GUID текущего сервера автовыбора в подписке (может быть устаревшим, см. currentTarget). */
    fun getCurrentGuid(subid: String): String? = MmkvManager.decodeSettingsString(guidKey(subid))

    /** Запоминает текущий сервер; подписка определяется по самому профилю. */
    fun setCurrentGuid(guid: String) {
        val profile = MmkvManager.decodeServerConfig(guid) ?: return
        val subid = profile.subscriptionId
        if (subid.isBlank()) return
        MmkvManager.encodeSettings(guidKey(subid), guid)
        // GUID меняются при каждом обновлении подписки, поэтому запоминаем ещё и адрес сервера
        MmkvManager.encodeSettings(idKey(subid), "${profile.server}:${profile.serverPort}")
    }

    /**
     * Сервер, на котором сейчас работает «Автовыбор» в подписке. Если сохранённый сервер
     * исчез (подписка обновилась) или стал российским, берётся лучший по сохранённой
     * задержке, а если замеров нет — первый кандидат.
     *
     * @return пара GUID + профиль или null, если кандидатов нет.
     */
    fun currentTarget(subid: String): Pair<String, ProfileItem>? {
        val all = candidates(subid)
        if (all.isEmpty()) return null

        getCurrentGuid(subid)?.let { saved ->
            all.firstOrNull { it.first == saved }?.let { return it }
        }

        // GUID устарел (подписку обновили) — ищем тот же сервер по адресу и порту
        MmkvManager.decodeSettingsString(idKey(subid))?.let { id ->
            all.firstOrNull { "${it.second.server}:${it.second.serverPort}" == id }?.let {
                setCurrentGuid(it.first)
                return it
            }
        }

        val best = all
            .map { it to (MmkvManager.decodeServerAffiliationInfo(it.first)?.testDelayMillis ?: 0L) }
            .filter { it.second > 0 }
            .minByOrNull { it.second }
            ?.first
            ?: all.first()

        setCurrentGuid(best.first)
        return best
    }

    /**
     * Для профиля «Автовыбор» возвращает GUID его текущего сервера, для остальных — тот же GUID.
     * Если у «Автовыбора» нет кандидатов, возвращает исходный GUID.
     */
    fun resolveGuid(guid: String): String {
        val profile = MmkvManager.decodeServerConfig(guid) ?: return guid
        if (!isAutoProfile(profile)) return guid
        return currentTarget(profile.subscriptionId)?.first ?: guid
    }

    /**
     * Проверяет кандидатов TCP-пингом (параллельно) и возвращает до TOP_N самых быстрых.
     * Вызывайте только когда текущий сервер не отвечает, а не по таймеру.
     *
     * @param exclude GUID, которые нужно пропустить (например, уже не сработавшие).
     * @return GUID, отсортированные по возрастанию пинга. Пустой список, если никто не ответил.
     */
    suspend fun pickBest(subid: String, exclude: Set<String> = emptySet()): List<String> = coroutineScope {
        val semaphore = Semaphore(TCP_PARALLELISM)
        candidates(subid)
            .filter { it.first !in exclude }
            .map { (guid, p) ->
                async(Dispatchers.IO) {
                    semaphore.withPermit {
                        guid to SpeedtestManager.tcping(p.server!!, p.serverPort!!.toInt())
                    }
                }
            }
            .awaitAll()
            .filter { it.second > 0 }
            .sortedBy { it.second }
            .take(TOP_N)
            .map { it.first }
    }

    // endregion
}

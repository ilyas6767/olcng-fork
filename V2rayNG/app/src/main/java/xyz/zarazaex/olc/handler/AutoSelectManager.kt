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
 * Закреплённый профиль «Автовыбор» в подписке «Белые списки».
 *
 * «Автовыбор» — маркерный профиль (POLICYGROUP с фиксированным remarks). При запуске он
 * превращается в обычный конфиг ОДНОГО сервера (см. currentTarget). Остальные серверы
 * проверяются только тогда, когда текущий перестал отвечать (см. pickBest).
 * Серверы из России в выборку не попадают (см. isRussian).
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

    /**
     * Сервер считается российским, если об этом говорит название (флаг, RU, Russia, Россия)
     * или если страна уже определена в кэше стран.
     */
    fun isRussian(profile: ProfileItem): Boolean {
        if (RU_REGEX.containsMatchIn(profile.remarks)) return true
        val server = profile.server ?: return false
        return MmkvManager.getCountryCache(server).equals("RU", ignoreCase = true)
    }

    // region профиль

    /**
     * Создаёт профиль «Автовыбор» (если его нет) и ставит его первым в списке подписки.
     * Работает только для подписки «Белые списки».
     *
     * @return GUID профиля «Автовыбор» или null, если подписка не подходит.
     */
    fun ensureProfile(subid: String): String? {
        if (subid.isBlank()) return null
        val sub = MmkvManager.decodeSubscription(subid) ?: return null
        if (sub.remarks != DefaultSubscriptions.WHITELIST_NAME) return null

        val list = MmkvManager.decodeServerList(subid)
        val existing = list.firstOrNull { guid ->
            isAutoProfile(MmkvManager.decodeServerConfig(guid))
        }

        if (existing != null) {
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
     * Кандидаты автовыбора: серверы подписки «Белые списки» без России, только TCP-протоколы
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

    /** GUID текущего сервера автовыбора (может быть устаревшим, проверяйте через currentTarget). */
    fun getCurrentGuid(): String? = MmkvManager.decodeSettingsString(KEY_CURRENT)

    fun setCurrentGuid(guid: String) {
        MmkvManager.encodeSettings(KEY_CURRENT, guid)
        // GUID меняются при каждом обновлении подписки, поэтому запоминаем ещё и адрес сервера
        MmkvManager.decodeServerConfig(guid)?.let {
            MmkvManager.encodeSettings(KEY_CURRENT_ID, "${it.server}:${it.serverPort}")
        }
    }

    /**
     * Сервер, на котором сейчас работает «Автовыбор». Если сохранённый сервер исчез
     * (подписка обновилась) или стал российским, берётся лучший по сохранённой задержке,
     * а если замеров нет — первый кандидат.
     *
     * @return пара GUID + профиль или null, если кандидатов нет.
     */
    fun currentTarget(subid: String): Pair<String, ProfileItem>? {
        val all = candidates(subid)
        if (all.isEmpty()) return null

        getCurrentGuid()?.let { saved ->
            all.firstOrNull { it.first == saved }?.let { return it }
        }

        // GUID устарел (подписку обновили) — ищем тот же сервер по адресу и порту
        MmkvManager.decodeSettingsString(KEY_CURRENT_ID)?.let { id ->
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

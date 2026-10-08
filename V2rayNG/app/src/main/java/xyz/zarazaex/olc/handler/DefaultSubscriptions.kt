package xyz.zarazaex.olc.handler

import xyz.zarazaex.olc.dto.SubscriptionItem

/**
 * Встроенные группы подписок. Группа = одна подписка, у которой в поле url
 * несколько ссылок, разделённых переводом строки. Содержимое всех ссылок
 * объединяется при обновлении (см. AngConfigManager.updateConfigViaSub).
 */
object DefaultSubscriptions {
    const val URL_SEPARATOR = "\n"
    private const val SEED_KEY = "default_sub_groups_hash"

    /** Ссылки из старой встроенной базы: такие подписки удаляются при первом запуске. */
    private val LEGACY_URL_PREFIXES = listOf(
        "https://raw.githubusercontent.com/igareck/vpn-configs-for-russia/",
        "https://raw.githubusercontent.com/zieng2/wl/",
        "https://raw.githubusercontent.com/whoahaow/rjsxrd/",
        "https://key.zarazaex.xyz/sub",
    )

    private val GROUPS: List<Pair<String, List<String>>> = listOf(
        "Черные списки" to listOf(
            "https://translate.yandex.ru/translate?url=https://gitlab.com/igareck/vpn-configs-for-russia/-/raw/main/BLACK_VLESS_RUS_mobile.txt&lang=de-de",
            "https://translate.yandex.ru/translate?url=https://gitlab.com/igareck/vpn-configs-for-russia/-/raw/main/BLACK_VLESS_RUS.txt&lang=de-de",
            "https://translate.yandex.ru/translate?url=https://gitlab.com/igareck/vpn-configs-for-russia/-/raw/main/BLACK_SS%2BAll_RUS.txt&lang=de-de",
            "https://translate.yandex.ru/translate?url=https://gitlab.com/igareck/vpn-configs-for-russia/-/raw/main/WHITE-SNI-RU-all.txt&lang=de-de",
            "https://hub.mos.ru/rkp/sub-roskompozor/raw/main/bl",
            "https://translate.yandex.ru/translate?url=https://raw.githubusercontent.com/ImSketch1337/vless-/refs/heads/main/BLWLservers.txt&lang=de-de",
            "https://sub.vlessfo.ru/vlessforu/working_configs.txt",
        ),
        "Белые списки" to listOf(
            "https://translate.yandex.ru/translate?url=https://raw.githack.com/igareck/vpn-configs-for-russia/main/Vless-Reality-White-Lists-Rus-Mobile.txt&lang=de-de",
            "https://translate.yandex.ru/translate?url=https://raw.githack.com/igareck/vpn-configs-for-russia/main/WHITE-CIDR-RU-all.txt&lang=de-de",
            "https://gitverse.ru/api/repos/Pizduk/PizdukVPN/raw/branch/master/WlSubPiz.txt",
            "https://gitverse.ru/api/repos/zieng2/wl/raw/branch/master/list_lite.txt",
            "https://gitverse.ru/api/repos/zieng2/wl/raw/branch/master/list_universal.txt",
            "https://translate.yandex.ru/translate?url=https://raw.githubusercontent.com/Maskkost93/kizyak-vpn-4.0/refs/heads/main/kizyakbeta7.txt&lang=de-de",
            "https://translate.yandex.ru/translate?url=https://raw.githubusercontent.com/Maskkost93/kizyak-vpn-4.0/refs/heads/main/kizyakbeta6.txt&lang=de-de",
            "https://hub.mos.ru/rkp/sub-roskompozor/raw/main/wl",
            "https://hub.mos.ru/kfwl/auto/raw/main/wl",
        ),
    )

    /** Выполняется один раз: убирает старые встроенные подписки и создаёт группы. */
        /** Пересоздаёт группы, если список ссылок в коде изменился. */
    fun seedIfNeeded() {
        val signature = GROUPS.toString().hashCode().toString()
        if (MmkvManager.decodeSettingsString(SEED_KEY) == signature) return

        val groupNames = GROUPS.map { it.first }.toSet()
        MmkvManager.decodeSubscriptions()
            .filter { sub ->
                sub.subscription.remarks in groupNames ||
                    LEGACY_URL_PREFIXES.any { sub.subscription.url.startsWith(it) }
            }
            .forEach { MmkvManager.removeSubscription(it.guid) }

        GROUPS.forEach { (name, urls) ->
            MmkvManager.encodeSubscription(
                "",
                SubscriptionItem(
                    remarks = name,
                    url = urls.joinToString(URL_SEPARATOR),
                    autoUpdate = true,
                )
            )
        }
        MmkvManager.encodeSettings(SEED_KEY, signature)
    }
}

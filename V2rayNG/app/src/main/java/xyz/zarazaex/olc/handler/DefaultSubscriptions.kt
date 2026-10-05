package xyz.zarazaex.olc.handler

import xyz.zarazaex.olc.dto.SubscriptionItem

/**
 * Встроенные группы подписок. Группа = одна подписка, у которой в поле url
 * несколько ссылок, разделённых переводом строки. Содержимое всех ссылок
 * объединяется при обновлении (см. AngConfigManager.updateConfigViaSub).
 */
object DefaultSubscriptions {
    const val URL_SEPARATOR = "\n"
    private const val SEED_KEY = "default_sub_groups_seeded_v1"

    /** Ссылки из старой встроенной базы: такие подписки удаляются при первом запуске. */
    private val LEGACY_URL_PREFIXES = listOf(
        "https://raw.githubusercontent.com/igareck/vpn-configs-for-russia/",
        "https://raw.githubusercontent.com/zieng2/wl/",
        "https://raw.githubusercontent.com/whoahaow/rjsxrd/",
        "https://key.zarazaex.xyz/sub",
    )

    private val GROUPS: List<Pair<String, List<String>>> = listOf(
        "Черные списки" to listOf(
            "https://gitlab.com/igareck/vpn-configs-for-russia/-/raw/main/BLACK_VLESS_RUS_mobile.txt",
            "https://gitlab.com/igareck/vpn-configs-for-russia/-/raw/main/BLACK_VLESS_RUS.txt",
            "https://gitlab.com/igareck/vpn-configs-for-russia/-/raw/main/BLACK_SS%2BAll_RUS.txt",
            "https://gitlab.com/igareck/vpn-configs-for-russia/-/raw/main/WHITE-SNI-RU-all.txt",
            "https://gitverse.ru/RKP_channel/RKP_bypass_configs/content/master/blacklist.txt",
        ),
        "Белые списки" to listOf(
            "https://translate.yandex.ru/translate?url=https://raw.githack.com/igareck/vpn-configs-for-russia/main/Vless-Reality-White-Lists-Rus-Mobile.txt&lang=de-de",
            "https://translate.yandex.ru/translate?url=https://raw.githack.com/igareck/vpn-configs-for-russia/main/WHITE-CIDR-RU-all.txt&lang=de-de",
            "https://gitverse.ru/api/repos/Pizduk/PizdukVPN/raw/branch/master/WlSubPiz.txt",
            "https://gitverse.ru/api/repos/zieng2/wl/raw/branch/master/list_lite.txt",
            "https://gitverse.ru/api/repos/zieng2/wl/raw/branch/master/list_universal.txt",
            "https://translate.yandex.ru/translate?url=https://raw.githubusercontent.com/Maskkost93/kizyak-vpn-4.0/refs/heads/main/kizyakbeta7.txt&lang=de-de",
            "https://translate.yandex.ru/translate?url=https://raw.githubusercontent.com/Maskkost93/kizyak-vpn-4.0/refs/heads/main/kizyakbeta6.txt&lang=de-de",
            "https://gitverse.ru/RKP_channel/RKP_bypass_configs/content/master/whitelist.txt",
            "https://translate.yandex.ru/translate?url=https://hub.mos.ru/kfwl/auto/raw/main/wl&lang=de-de",
        ),
    )

    /** Выполняется один раз: убирает старые встроенные подписки и создаёт группы. */
    fun seedIfNeeded() {
        if (MmkvManager.decodeSettingsBool(SEED_KEY, false)) return

        MmkvManager.decodeSubscriptions()
            .filter { sub -> LEGACY_URL_PREFIXES.any { sub.subscription.url.startsWith(it) } }
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
        MmkvManager.encodeSettings(SEED_KEY, true)
    }
}

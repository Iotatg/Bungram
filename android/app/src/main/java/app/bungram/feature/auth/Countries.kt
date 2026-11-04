package app.bungram.feature.auth

import android.content.Context
import android.telephony.TelephonyManager
import java.util.Locale

/**
 * Country dial codes for the phone-number step.
 *
 * The table is a plain fact table (ISO 3166 alpha-2 -> E.164 country code); display names come
 * from the platform locale, so the list is correct in every language without shipping 240
 * translated strings. Flags are derived from the ISO code, not stored as images.
 */
data class Country(
    val iso: String,
    val dial: String,
    val name: String,
) {
    /** Regional-indicator pair, e.g. "ID" -> 🇮🇩. */
    val flag: String = iso.map { 0x1F1E6 + (it - 'A') }
        .joinToString("") { String(Character.toChars(it)) }

    val display: String get() = "$flag  $name  +$dial"
}

object Countries {

    /** ISO2:dial, comma separated. One entry per assigned country calling code. */
    private const val RAW = "AD:376,AE:971,AF:93,AG:1268,AI:1264,AL:355,AM:374,AO:244,AR:54,AT:43," +
        "AU:61,AW:297,AZ:994,BA:387,BB:1246,BD:880,BE:32,BF:226,BG:359,BH:973,BI:257,BJ:229," +
        "BL:590,BM:1441,BN:673,BO:591,BQ:599,BR:55,BS:1242,BT:975,BW:267,BY:375,BZ:501,CA:1," +
        "CD:243,CF:236,CG:242,CH:41,CI:225,CK:682,CL:56,CM:237,CN:86,CO:57,CR:506,CU:53,CV:238," +
        "CW:599,CY:357,CZ:420,DE:49,DJ:253,DK:45,DM:1767,DO:1809,DZ:213,EC:593,EE:372,EG:20," +
        "EH:212,ER:291,ES:34,ET:251,FI:358,FJ:679,FK:500,FM:691,FO:298,FR:33,GA:241,GB:44," +
        "GD:1473,GE:995,GF:594,GG:44,GH:233,GI:350,GL:299,GM:220,GN:224,GP:590,GQ:240,GR:30," +
        "GT:502,GU:1671,GW:245,GY:592,HK:852,HN:504,HR:385,HT:509,HU:36,ID:62,IE:353,IL:972," +
        "IM:44,IN:91,IQ:964,IR:98,IS:354,IT:39,JE:44,JM:1876,JO:962,JP:81,KE:254,KG:996,KH:855," +
        "KI:686,KM:269,KN:1869,KP:850,KR:82,KW:965,KY:1345,KZ:7,LA:856,LB:961,LC:1758,LI:423," +
        "LK:94,LR:231,LS:266,LT:370,LU:352,LV:371,LY:218,MA:212,MC:377,MD:373,ME:382,MF:590," +
        "MG:261,MH:692,MK:389,ML:223,MM:95,MN:976,MO:853,MP:1670,MQ:596,MR:222,MS:1664,MT:356," +
        "MU:230,MV:960,MW:265,MX:52,MY:60,MZ:258,NA:264,NC:687,NE:227,NF:672,NG:234,NI:505," +
        "NL:31,NO:47,NP:977,NR:674,NU:683,NZ:64,OM:968,PA:507,PE:51,PF:689,PG:675,PH:63," +
        "PK:92,PL:48,PM:508,PR:1787,PS:970,PT:351,PW:680,PY:595,QA:974,RE:262,RO:40,RS:381," +
        "RU:7,RW:250,SA:966,SB:677,SC:248,SD:249,SE:46,SG:65,SH:290,SI:386,SJ:47,SK:421,SL:232," +
        "SM:378,SN:221,SO:252,SR:597,SS:211,ST:239,SV:503,SX:1721,SY:963,SZ:268,TC:1649,TD:235," +
        "TG:228,TH:66,TJ:992,TK:690,TL:670,TM:993,TN:216,TO:676,TR:90,TT:1868,TV:688,TW:886," +
        "TZ:255,UA:380,UG:256,US:1,UY:598,UZ:998,VA:39,VC:1784,VE:58,VG:1284,VI:1340,VN:84," +
        "VU:678,WF:681,WS:685,XK:383,YE:967,YT:262,ZA:27,ZM:260,ZW:263"

    /** Shown at the top of the picker, the way every messenger does it. */
    val commonIso = listOf("US", "GB", "IN", "ID", "BR", "DE", "RU", "FR")

    val all: List<Country> by lazy {
        val locale = Locale.getDefault()
        RAW.split(',').mapNotNull { entry ->
            val parts = entry.split(':')
            if (parts.size != 2) return@mapNotNull null
            val iso = parts[0]
            val name = runCatching { Locale("", iso).getDisplayCountry(locale) }
                .getOrDefault(iso)
                .ifBlank { iso }
            Country(iso = iso, dial = parts[1], name = name)
        }.sortedWith(compareBy({ it.name.lowercase(locale) }, { it.dial }))
    }

    val common: List<Country> by lazy {
        commonIso.mapNotNull { iso -> all.firstOrNull { it.iso == iso } }
    }

    fun byIso(iso: String?): Country? =
        iso?.takeIf { it.length == 2 }?.let { code -> all.firstOrNull { it.iso.equals(code, true) } }

    /**
     * SIM first, then the network, then the device locale. All three can be wrong (a traveller
     * keeps their home SIM), which is why the picker is one tap away and pre-selected, not hidden.
     */
    fun detect(context: Context): Country {
        val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
        val iso = runCatching { tm?.simCountryIso }.getOrNull()
            ?.takeIf { it.length == 2 }
            ?: runCatching { tm?.networkCountryIso }.getOrNull()?.takeIf { it.length == 2 }
            ?: Locale.getDefault().country
        return byIso(iso) ?: all.first { it.iso == "US" }
    }

    /** Groups national digits 3-4-4 as the user types; keeps the caret at the end. */
    fun formatNational(digits: String): String {
        val d = digits.filter(Char::isDigit).take(12)
        val out = StringBuilder()
        d.forEachIndexed { i, c ->
            if (i == 3 || i == 6 || i == 9) out.append(' ')
            out.append(c)
        }
        return out.toString()
    }

    fun e164(country: Country, national: String): String =
        "+" + country.dial + national.filter(Char::isDigit)
}

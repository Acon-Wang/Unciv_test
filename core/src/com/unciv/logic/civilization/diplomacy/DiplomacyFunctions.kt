package com.unciv.logic.civilization.diplomacy

import com.unciv.UncivGame
import com.unciv.logic.civilization.AlertType
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.NotificationCategory
import com.unciv.logic.civilization.NotificationIcon
import com.unciv.logic.civilization.PopupAlert
import com.unciv.logic.map.mapunit.movement.UnitMovement
import com.unciv.logic.map.tile.Tile
import com.unciv.models.ruleset.unique.UniqueType
import com.unciv.models.stats.Stat
import com.unciv.models.stats.Stats
import kotlin.math.max

class DiplomacyFunctions(val civInfo: Civilization){

    /** A sorted Sequence of all other civs we know (excluding barbarians and spectators) */
    /**
     * 得到已知城市的排序
     */
    fun getKnownCivsSorted(includeCityStates: Boolean = true, includeDefeated: Boolean = false) =
        civInfo.gameInfo.getCivsSorted(includeCityStates, includeDefeated) {
            it != civInfo && civInfo.knows(it)
        }

    /**
     * 制造文明的认识，调用meetCiv()
     */
    fun makeCivilizationsMeet(otherCiv: Civilization, warOnContact: Boolean = false) {
        meetCiv(otherCiv, warOnContact)
        otherCiv.diplomacyFunctions.meetCiv(civInfo, warOnContact)
    }

    /**
     *遇到新文明后发生的一些事情，规则判断以及给钱给礼物等。
     */
    private fun meetCiv(otherCiv: Civilization, warOnContact: Boolean = false) {
        civInfo.diplomacy[otherCiv.civName] = DiplomacyManager(civInfo, otherCiv.civName)
            .apply { diplomaticStatus = DiplomaticStatus.Peace }

        if (!otherCiv.isSpectator())
            otherCiv.popupAlerts.add(PopupAlert(AlertType.FirstContact, civInfo.civName))

        if (civInfo.isCurrentPlayer())
            UncivGame.Current.settings.addCompletedTutorialTask("Meet another civilization")


        if (civInfo.isCityState() && otherCiv.isMajorCiv()) {
            if (warOnContact || otherCiv.isMinorCivAggressor()) return // No gift if they are bad people, or we are just about to be at war

            val cityStateLocation = if (civInfo.cities.isEmpty()) null else civInfo.getCapital()!!.location

            val giftAmount = Stats(gold = 15f)
            val faithAmount = Stats(faith = 4f)
            // Later, religious city-states will also gift gold, making this the better implementation
            // For now, it might be overkill though.
            var meetString = "[${civInfo.civName}] has given us [${giftAmount}] as a token of goodwill for meeting us"
            val religionMeetString = "[${civInfo.civName}] has also given us [${faithAmount}]"
            if (civInfo.diplomacy.count { it.value.otherCiv().isMajorCiv() } == 1) {
                giftAmount.timesInPlace(2f)
                meetString = "[${civInfo.civName}] has given us [${giftAmount}] as we are the first major civ to meet them"
            }
            if (cityStateLocation != null)
                otherCiv.addNotification(meetString, cityStateLocation, NotificationCategory.Diplomacy, NotificationIcon.Gold)
            else
                otherCiv.addNotification(meetString, NotificationCategory.Diplomacy, NotificationIcon.Gold)

            if (otherCiv.isCityState() && otherCiv.cityStateFunctions.canProvideStat(Stat.Faith)){
                otherCiv.addNotification(religionMeetString, NotificationCategory.Diplomacy, NotificationIcon.Faith)

                for ((key, value) in faithAmount)
                    otherCiv.addStat(key, value.toInt())
            }
            for ((key, value) in giftAmount)
                otherCiv.addStat(key, value.toInt())

            if (civInfo.cities.isNotEmpty())
                civInfo.getCapital()?.getCenterTile()?.setExplored(otherCiv, true)

            civInfo.questManager.justMet(otherCiv) // Include them in war with major pseudo-quest
        }
    }

    /**
     * 判断是否于其在战争，排除野蛮人与没遇到的情况
     */
    fun isAtWarWith(otherCiv: Civilization): Boolean {
        return when {
            otherCiv == civInfo -> false
            otherCiv.isBarbarian() || civInfo.isBarbarian() -> true
            else -> {
                val diplomacyManager = civInfo.diplomacy[otherCiv.civName]
                    ?: return false // not encountered yet
                return diplomacyManager.diplomaticStatus == DiplomaticStatus.War
            }
        }
    }

    /**
     * 判断是否能够与该城邦发起友好
     */
    fun canSignDeclarationOfFriendshipWith(otherCiv: Civilization): Boolean {
        return otherCiv.isMajorCiv() && !otherCiv.isAtWarWith(civInfo)
            && !civInfo.getDiplomacyManager(otherCiv).hasFlag(DiplomacyFlags.Denunciation)
            && !civInfo.getDiplomacyManager(otherCiv).hasFlag(DiplomacyFlags.DeclarationOfFriendship)
    }

    /**
     * 判断是否能否发起研究协定
     */
    fun canSignResearchAgreement(): Boolean {
        if (!civInfo.isMajorCiv()) return false
        if (!civInfo.hasUnique(UniqueType.EnablesResearchAgreements)) return false
        if (civInfo.gameInfo.ruleset.technologies.values
                    .none { civInfo.tech.canBeResearched(it.name) && !civInfo.tech.isResearched(it.name) }) return false
        return true
    }


    /**
     * 判断是否可以不用花费进行研究协定
     */
    fun canSignResearchAgreementNoCostWith (otherCiv: Civilization): Boolean {
        val diplomacyManager = civInfo.getDiplomacyManager(otherCiv)
        return canSignResearchAgreement() && otherCiv.diplomacyFunctions.canSignResearchAgreement()
//             && diplomacyManager.hasFlag(DiplomacyFlags.DeclarationOfFriendship)
            && !diplomacyManager.hasFlag(DiplomacyFlags.ResearchAgreement)
            && !diplomacyManager.otherCivDiplomacy().hasFlag(DiplomacyFlags.ResearchAgreement)
    }

    /**
     * 能否与该城邦发起研究协议
     */
    fun canSignResearchAgreementsWith(otherCiv: Civilization): Boolean {
        val cost = getResearchAgreementCost(otherCiv)
        return canSignResearchAgreementNoCostWith(otherCiv)
            && civInfo.gold >= cost && otherCiv.gold >= cost
    }

    fun canSignResearchAgreementsWith_easy(otherCiv: Civilization):Pair<Boolean, Map<String, List<String>>> {
        val cost = getResearchAgreementCost(otherCiv)
        var Reason_consent = mutableListOf<String>()
        var Reason_reject = mutableListOf<String>()
        var flag = 0
        if (!civInfo.isMajorCiv()) {
            flag++
            Reason_reject.add("We're not a major city")
        }
        if (!civInfo.hasUnique(UniqueType.EnablesResearchAgreements)){
            flag++
            Reason_reject.add("We didn't study the properties")
        }
        if (civInfo.gameInfo.ruleset.technologies.values
                .none { civInfo.tech.canBeResearched(it.name) && !civInfo.tech.isResearched(it.name) }) {
            flag++
            Reason_reject.add("We don't have anything to study")
        }

        if (!otherCiv.isMajorCiv()) {
            flag++
            Reason_reject.add("You're not a major city")
        }
        if (!otherCiv.hasUnique(UniqueType.EnablesResearchAgreements)) {
            flag++
            Reason_reject.add("You didn't study the properties")
        }
        if (otherCiv.gameInfo.ruleset.technologies.values
                .none { otherCiv.tech.canBeResearched(it.name) && !otherCiv.tech.isResearched(it.name) }) {
            flag++
            Reason_reject.add("Your don't have anything to study")
        }
        if (civInfo.gold<cost){
            flag++
            Reason_reject.add("We can't afford to pay")
        }
        if (otherCiv.gold<cost){
            flag++
            Reason_reject.add("You can't afford to pay")
        }
        Reason_consent.add("We can initiate research agreements and have things to study")
        Reason_consent.add("We can all pay for it")
        val reasonsDict: Map<String, List<String>> = mapOf("consent" to Reason_consent, "reject" to Reason_reject)
        if (flag>0) return Pair(false,reasonsDict)
        else return Pair(true,reasonsDict)
    }

    /**
     * 得到研究协定的花费。
     */
    fun getResearchAgreementCost(otherCiv: Civilization): Int {
        // https://forums.civfanatics.com/resources/research-agreements-bnw.25568/
        return ( max(civInfo.getEra().researchAgreementCost, otherCiv.getEra().researchAgreementCost)
                    * civInfo.gameInfo.speed.goldCostModifier
            ).toInt()
    }

    /**
     * 判断自己能否提出防御协定
     */
    fun canSignDefensivePact(): Boolean {
        if (!civInfo.isMajorCiv()) return false
        if (!civInfo.hasUnique(UniqueType.EnablesDefensivePacts)) return false
        return true
    }

    /**
     * 判断能否与该城邦签订防御协定
     */
    fun canSignDefensivePactWith(otherCiv: Civilization): Boolean {
        val diplomacyManager = civInfo.getDiplomacyManager(otherCiv)
        return canSignDefensivePact() && otherCiv.diplomacyFunctions.canSignDefensivePact()
//             && (diplomacyManager.hasFlag(DiplomacyFlags.DeclarationOfFriendship)
//             || diplomacyManager.otherCivDiplomacy().hasFlag(DiplomacyFlags.DeclarationOfFriendship))
            && !diplomacyManager.hasFlag(DiplomacyFlags.DefensivePact)
            && !diplomacyManager.otherCivDiplomacy().hasFlag(DiplomacyFlags.DefensivePact)
            && diplomacyManager.diplomaticStatus != DiplomaticStatus.DefensivePact
    }


    /**
     * @returns whether units of this civilization can pass through the tiles owned by [otherCiv],
     * considering only civ-wide filters.
     * Use [Tile.canCivPassThrough] to check whether units of a civilization can pass through
     * a specific tile, considering only civ-wide filters.
     * Use [UnitMovement.canPassThrough] to check whether a specific unit can pass through
     * a specific tile.
     */
    /**
     * 判断是否能过通过这个文明的Tiles
     */
    fun canPassThroughTiles(otherCiv: Civilization): Boolean {
        if (otherCiv == civInfo) return true
        if (otherCiv.isBarbarian()) return true
        if (civInfo.isBarbarian() && civInfo.gameInfo.turns >= civInfo.gameInfo.difficultyObject.turnBarbariansCanEnterPlayerTiles)
            return true
        val diplomacyManager = civInfo.diplomacy[otherCiv.civName]
        if (diplomacyManager != null && (diplomacyManager.hasOpenBorders || diplomacyManager.diplomaticStatus == DiplomaticStatus.War))
            return true
        // Players can always pass through city-state tiles
        if (civInfo.isHuman() && otherCiv.isCityState()) return true
        return false
    }



}

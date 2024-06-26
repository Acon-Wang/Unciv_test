package com.unciv.logic.automation.civilization

import com.unciv.Constants
import com.unciv.ContentData
import com.unciv.json.json
import com.unciv.logic.automation.Automation
import com.unciv.logic.automation.ThreatLevel
import com.unciv.logic.battle.BattleDamage
import com.unciv.logic.battle.CityCombatant
import com.unciv.logic.battle.MapUnitCombatant
import com.unciv.logic.civilization.AlertType
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.PopupAlert
import com.unciv.logic.civilization.diplomacy.DiplomacyFlags
import com.unciv.logic.civilization.diplomacy.DiplomaticStatus
import com.unciv.logic.civilization.diplomacy.RelationshipLevel
import com.unciv.logic.files.UncivFiles
import com.unciv.logic.map.BFS
import com.unciv.logic.map.tile.Tile
import com.unciv.logic.trade.TradeEvaluation
import com.unciv.logic.trade.TradeLogic
import com.unciv.logic.trade.TradeOffer
import com.unciv.logic.trade.TradeRequest
import com.unciv.logic.trade.TradeType
import com.unciv.models.ruleset.Building
import com.unciv.models.ruleset.Victory
import com.unciv.models.ruleset.unique.UniqueType
import com.unciv.models.ruleset.unit.BaseUnit
import com.unciv.models.translations.tr
import com.unciv.ui.screens.victoryscreen.RankingType
import com.unciv.utils.DebugUtils
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

object DiplomacyAutomation {

    /**
     * 提议外交建立友好关系，根据能够建交的城市，按照其relationshipLevel降序排列
     */
    internal fun offerDeclarationOfFriendship(civInfo: Civilization,post: Boolean=true) {
        val civsThatWeCanDeclareFriendshipWith = civInfo.getKnownCivs()//获取已知文明
            .filter { civInfo.diplomacyFunctions.canSignDeclarationOfFriendshipWith(it)
                && !civInfo.getDiplomacyManager(it).hasFlag(DiplomacyFlags.DeclinedDeclarationOfFriendship)}
            .sortedByDescending { it.getDiplomacyManager(civInfo).relationshipLevel() }.toList()
//         val content = UncivFiles.gameInfoToString(civInfo.gameInfo,false,false)
        for (otherCiv in civsThatWeCanDeclareFriendshipWith) {
            // Default setting is 2, this will be changed according to different civ.
            //random随机符合
            var jsonString: String
            if (post&&DebugUtils.NEED_POST&&!DebugUtils.SIMULATEING){
                if (DebugUtils.NEED_GameInfo){
                    val content = UncivFiles.gameInfoToString(civInfo.gameInfo,false,false)
                    var contentData = ContentData_four(content, civInfo.civName,otherCiv.civName,"change_closeness")
                    jsonString = Json.encodeToString(contentData)
                }
                else {
                    var contentData = ContentData_three("change_closeness", civInfo.civName, otherCiv.civName)
                    jsonString = Json.encodeToString(contentData)
                }
//                 val postRequestResult = sendPostRequest("http://127.0.0.1:2337/wantsToSignDeclarationOfFrienship", jsonString)
                val postRequestResult = sendPostRequest("http://127.0.0.1:2337/get_tools", jsonString)
                val jsonObject = Json.parseToJsonElement(postRequestResult)
                val resultElement = jsonObject.jsonObject["result"]
                val resultValue: Boolean? = if (resultElement is JsonPrimitive && resultElement.contentOrNull != null) {
                    resultElement.contentOrNull!!.toBoolean() // 尝试将内容转换为布尔值
                } else {
                    null // 处理 "result" 不是布尔值或字段不存在的情况
                }
                if (resultValue == true) {
                    otherCiv.popupAlerts.add(PopupAlert(AlertType.DeclarationOfFriendship, civInfo.civName))
                }
            }
            else{
                if ((1..10).random() <= 2 && wantsToSignDeclarationOfFrienship(civInfo, otherCiv)) {
                    otherCiv.popupAlerts.add(PopupAlert(AlertType.DeclarationOfFriendship, civInfo.civName))
                }
            }

        }
    }

    /**
     * 尝试去建交，通过一系列的评估方式，确定motivation的值，如果大于0则返回true。
     */
    internal fun wantsToSignDeclarationOfFrienship(civInfo: Civilization, otherCiv: Civilization): Boolean {
        val diploManager = civInfo.getDiplomacyManager(otherCiv)
        // Shortcut, if it is below favorable then don't consider it
        //如果该城市的有利程度不够，则不考虑
        if (diploManager.isRelationshipLevelLT(RelationshipLevel.Favorable)) return false

        val numOfFriends = civInfo.diplomacy.count { it.value.hasFlag(DiplomacyFlags.DeclarationOfFriendship) }
        val knownCivs = civInfo.getKnownCivs().count { it.isMajorCiv() && it.isAlive() }
        val allCivs = civInfo.gameInfo.civilizations.count { it.isMajorCiv() } - 1 // Don't include us
        val deadCivs = civInfo.gameInfo.civilizations.count { it.isMajorCiv() && !it.isAlive() }
        val allAliveCivs = allCivs - deadCivs

        // Motivation should be constant as the number of civs changes
        //动机一直不随文明数量而变
        var motivation = diploManager.opinionOfOtherCiv().toInt() - 40//减40数值

        // If the other civ is stronger than we are compelled to be nice to them
        // If they are too weak, then thier friendship doesn't mean much to us
        motivation += when (Automation.threatAssessment(civInfo,otherCiv)) {
            ThreatLevel.VeryHigh -> 10
            ThreatLevel.High -> 5
            ThreatLevel.VeryLow -> -5
            else -> 0
        }

        // Try to ally with a fourth of the civs in play
        //尽量与1/4存活的国家建交。
        val civsToAllyWith = 0.25f * allAliveCivs

        if (numOfFriends < civsToAllyWith) {//当自己的朋友少于我们的目标数时
            // Goes from 10 to 0 once the civ gets 1/4 of all alive civs as friends 当一个文明获得了1/4存活文明的朋友时，这个文明从10变成0
            motivation += (10 - 10 * (numOfFriends / civsToAllyWith)).toInt()
        } else {
            // Goes form 0 to -120 as the civ gets more friends, offset by civsToAllyWith
            //朋友过多了，减少动机
            motivation -= (120f * (numOfFriends - civsToAllyWith) / (knownCivs - civsToAllyWith)).toInt()
        }

        // Goes from 0 to -50 as more civs die
        // this is meant to prevent the game from stalemating when a group of friends
        // conquers all oposition
        //就是为了防止游戏进程后期友军过多。
        motivation -= deadCivs / allCivs * 50

        // Wait to declare frienships until more civs
        // Goes from -30 to 0 when we know 75% of allCivs
        val civsToKnow = 0.75f * allAliveCivs
        motivation -= ((civsToKnow - knownCivs) / civsToKnow * 30f).toInt().coerceAtLeast(0)

        motivation -= hasAtLeastMotivationToAttack(civInfo, otherCiv, motivation / 2) * 2

        return motivation > 0
    }
    fun wantsToSignDeclarationOfFrienship_civsim(civInfo: Civilization, otherCiv: Civilization): Pair<Boolean, Map<String, List<String>>> {
        var Reason_consent = mutableListOf<String>()
        var Reason_reject = mutableListOf<String>()
        val diploManager = civInfo.getDiplomacyManager(otherCiv)
        // Shortcut, if it is below favorable then don't consider it
        //如果该城市的有利程度不够，则不考虑
        if (diploManager.isRelationshipLevelLT(RelationshipLevel.Favorable)){
            Reason_reject.add("To our disadvantage")
//             return Reason_reject
        }

        val numOfFriends = civInfo.diplomacy.count { it.value.hasFlag(DiplomacyFlags.DeclarationOfFriendship) }
        val knownCivs = civInfo.getKnownCivs().count { it.isMajorCiv() && it.isAlive() }
        val allCivs = civInfo.gameInfo.civilizations.count { it.isMajorCiv() } - 1 // Don't include us
        val deadCivs = civInfo.gameInfo.civilizations.count { it.isMajorCiv() && !it.isAlive() }
        val allAliveCivs = allCivs - deadCivs

        // Motivation should be constant as the number of civs changes
        //动机一直不随文明数量而变
        var motivation = diploManager.opinionOfOtherCiv().toInt() - 40//减40数值

        // If the other civ is stronger than we are compelled to be nice to them
        // If they are too weak, then thier friendship doesn't mean much to us
        motivation += when (Automation.threatAssessment(civInfo,otherCiv)) {
            ThreatLevel.VeryHigh -> 10
            ThreatLevel.High -> 5
            ThreatLevel.VeryLow -> -5
            else -> 0
        }
        when (Automation.threatAssessment(civInfo,otherCiv)) {
            ThreatLevel.VeryHigh -> Reason_consent.add("You are very strong")
            ThreatLevel.High -> Reason_consent.add("You are very good")
            ThreatLevel.VeryLow -> Reason_reject.add("You're so much worse than me")
            else -> 0
        }

        // Try to ally with a fourth of the civs in play
        //尽量与1/4存活的国家建交。
        val civsToAllyWith = 0.25f * allAliveCivs

        if (numOfFriends < civsToAllyWith) {//当自己的朋友少于我们的目标数时
            // Goes from 10 to 0 once the civ gets 1/4 of all alive civs as friends 当一个文明获得了1/4存活文明的朋友时，这个文明从10变成0
            Reason_consent.add("Need to make more friends")
            motivation += (10 - 10 * (numOfFriends / civsToAllyWith)).toInt()
        } else {
            // Goes form 0 to -120 as the civ gets more friends, offset by civsToAllyWith
            //朋友过多了，减少动机
            Reason_reject.add("I've made too many friends")
            motivation -= (120f * (numOfFriends - civsToAllyWith) / (knownCivs - civsToAllyWith)).toInt()
        }

        // Goes from 0 to -50 as more civs die
        // this is meant to prevent the game from stalemating when a group of friends
        // conquers all oposition
        //就是为了防止游戏进程后期友军过多。
        Reason_reject.add("There are too many friendly forces")
        motivation -= deadCivs / allCivs * 50

        // Wait to declare frienships until more civs
        // Goes from -30 to 0 when we know 75% of allCivs
        val civsToKnow = 0.75f * allAliveCivs
        motivation -= ((civsToKnow - knownCivs) / civsToKnow * 30f).toInt().coerceAtLeast(0)
        Reason_reject.add("I've met so few civilizations now")

        motivation -= hasAtLeastMotivationToAttack(civInfo, otherCiv, motivation / 2) * 2

        Reason_consent.add(Integer.toString(motivation))
        Reason_reject.add(Integer.toString(motivation))
        val reasonsDict: Map<String, List<String>> = mapOf("consent" to Reason_consent, "reject" to Reason_reject)
        if (motivation>0)return Pair(true,reasonsDict)
        else return Pair(false,reasonsDict)
    }
    /**
     *  提议开放边界，根据能够开放的城市，按照其relationshipLevel降序排列
     */
    internal fun offerOpenBorders(civInfo: Civilization,post: Boolean=true) {
        if (!civInfo.hasUnique(UniqueType.EnablesOpenBorders)) return
        val civsThatWeCanOpenBordersWith = civInfo.getKnownCivs()
            .filter { it.isMajorCiv() && !civInfo.isAtWarWith(it)//不在战争
                && it.hasUnique(UniqueType.EnablesOpenBorders)//能够开放
                && !civInfo.getDiplomacyManager(it).hasOpenBorders
                && !civInfo.getDiplomacyManager(it).hasFlag(DiplomacyFlags.DeclinedOpenBorders) }
            .sortedByDescending { it.getDiplomacyManager(civInfo).relationshipLevel() }.toList()
//         val content = UncivFiles.gameInfoToString(civInfo.gameInfo,false,false)
        var jsonString: String
        for (otherCiv in civsThatWeCanOpenBordersWith) {
            // Default setting is 3, this will be changed according to different civ.
            //random随机符合
            if(post&&DebugUtils.NEED_POST&&!DebugUtils.SIMULATEING){
                if (DebugUtils.NEED_GameInfo) {
                    val content = UncivFiles.gameInfoToString(civInfo.gameInfo, false, false)
                    var contentData =
                        ContentData_four(content, civInfo.civName, otherCiv.civName, "open_borders")
                    jsonString = Json.encodeToString(contentData)
                }
                else {
                    val contentData =
                        ContentData_three("open_borders", civInfo.civName, otherCiv.civName)
                    jsonString = Json.encodeToString(contentData)
                }
                val postRequestResult = sendPostRequest("http://127.0.0.1:2337/get_tools", jsonString)
                val jsonObject = Json.parseToJsonElement(postRequestResult)
                val resultElement = jsonObject.jsonObject["result"]
                val resultValue: Boolean? = if (resultElement is JsonPrimitive && resultElement.contentOrNull != null) {
                    resultElement.contentOrNull!!.toBoolean() // 尝试将内容转换为布尔值
                } else {
                    null // 处理 "result" 不是布尔值或字段不存在的情况
                }
                if ( resultValue==true) {

                    val tradeLogic = TradeLogic(civInfo, otherCiv)
                    tradeLogic.currentTrade.ourOffers.add(TradeOffer(Constants.openBorders, TradeType.Agreement))
                    tradeLogic.currentTrade.theirOffers.add(TradeOffer(Constants.openBorders, TradeType.Agreement))

                    otherCiv.tradeRequests.add(TradeRequest(civInfo.civName, tradeLogic.currentTrade.reverse()))
                }
            }
            else{
                if ((1..10).random() <= 3 && wantsToOpenBorders(civInfo, otherCiv)) {
                    val tradeLogic = TradeLogic(civInfo, otherCiv)
                    tradeLogic.currentTrade.ourOffers.add(TradeOffer(Constants.openBorders, TradeType.Agreement))
                    tradeLogic.currentTrade.theirOffers.add(TradeOffer(Constants.openBorders, TradeType.Agreement))

                    otherCiv.tradeRequests.add(TradeRequest(civInfo.civName, tradeLogic.currentTrade.reverse()))
                }
            }

        }
    }

    /**
     * 尝试去开放边界，根据几个条件判断是否合适，如合适则返回true。
     */
    fun wantsToOpenBorders(civInfo: Civilization, otherCiv: Civilization): Boolean {
        if (civInfo.getDiplomacyManager(otherCiv).isRelationshipLevelLT(RelationshipLevel.Favorable)) return false
        // Don't accept if they are at war with our friends, they might use our land to attack them
        if (civInfo.diplomacy.values.any { it.isRelationshipLevelGE(RelationshipLevel.Friend) && it.otherCiv().isAtWarWith(otherCiv)})
            return false
        if (hasAtLeastMotivationToAttack(civInfo, otherCiv, (civInfo.getDiplomacyManager(otherCiv).opinionOfOtherCiv()/ 2 - 10).toInt()) >= 0)
            return false
        return true
    }
    fun wantsToOpenBorders_civsim(civInfo: Civilization, otherCiv: Civilization): Pair<Boolean, Map<String, List<String>>>{
        var Reason_consent = mutableListOf<String>()
        var Reason_reject = mutableListOf<String>()
        var flag = 0
        var score = 0
        if (civInfo.getDiplomacyManager(otherCiv).isRelationshipLevelLT(RelationshipLevel.Favorable)){
            Reason_reject.add("We don't have a good relationship")
            score-=50
            flag++
        }
        // Don't accept if they are at war with our friends, they might use our land to attack them
        if (civInfo.diplomacy.values.any { it.isRelationshipLevelGE(RelationshipLevel.Friend) && it.otherCiv().isAtWarWith(otherCiv)}){
            Reason_reject.add("Don't accept if they are at war with our friends, they might use our land to attack them")
            score-=50
            flag++
        }

        if (hasAtLeastMotivationToAttack(civInfo, otherCiv, (civInfo.getDiplomacyManager(otherCiv).opinionOfOtherCiv()/ 2 - 10).toInt()) >= 0){
            Reason_reject.add("We can attack it, so we don't have to open the border")
            score-=50
            flag++
        }
        if (flag>0){
            Reason_reject.add(Integer.toString(score))
            val reasonsDict: Map<String, List<String>> = mapOf("consent" to Reason_consent, "reject" to Reason_reject)
            return Pair(false,reasonsDict)
        }
        Reason_consent.add("We have a good relationship")
        score+=10
        Reason_consent.add("You're not at war with my friends")
        score+=10
        Reason_consent.add("We don't have to go to war")
        score+=10
        Reason_consent.add(Integer.toString(score))
        val reasonsDict: Map<String, List<String>> = mapOf("consent" to Reason_consent, "reject" to Reason_reject)
        return Pair(true,reasonsDict)
    }

    /**
     * 提议研究协定。。
     */
    internal fun offerResearchAgreement(civInfo: Civilization,post: Boolean=true) {
        if (!civInfo.diplomacyFunctions.canSignResearchAgreement()) return // don't waste your time
//         val content = UncivFiles.gameInfoToString(civInfo.gameInfo,false,false)
        if (post&&DebugUtils.NEED_POST&&!DebugUtils.SIMULATEING){
            val canSignResearchAgreementCiv = civInfo.getKnownCivs()
                .filter {
//                 civInfo.diplomacyFunctions.canSignResearchAgreementsWith(it)
                    sendcanSignResearchAgreementsWith("research_agreement",civInfo,it) == true && !civInfo.getDiplomacyManager(it).hasFlag(DiplomacyFlags.DeclinedResearchAgreement)
                }
                .sortedByDescending { it.stats.statsForNextTurn.science }
            for (otherCiv in canSignResearchAgreementCiv) {
                // Default setting is 5, this will be changed according to different civ.
//                 if ((1..10).random() > 5) continue
                val tradeLogic = TradeLogic(civInfo, otherCiv)
                val cost = civInfo.diplomacyFunctions.getResearchAgreementCost(otherCiv)
                tradeLogic.currentTrade.ourOffers.add(TradeOffer(Constants.researchAgreement, TradeType.Treaty, cost))
                tradeLogic.currentTrade.theirOffers.add(TradeOffer(Constants.researchAgreement, TradeType.Treaty, cost))

                otherCiv.tradeRequests.add(TradeRequest(civInfo.civName, tradeLogic.currentTrade.reverse()))
            }
        }
        else{
            val canSignResearchAgreementCiv = civInfo.getKnownCivs()
                .filter {
                    civInfo.diplomacyFunctions.canSignResearchAgreementsWith(it)
                        && !civInfo.getDiplomacyManager(it).hasFlag(DiplomacyFlags.DeclinedResearchAgreement)
                }
                .sortedByDescending { it.stats.statsForNextTurn.science }

            for (otherCiv in canSignResearchAgreementCiv) {
                // Default setting is 5, this will be changed according to different civ.
                if ((1..10).random() > 5) continue
                val tradeLogic = TradeLogic(civInfo, otherCiv)
                val cost = civInfo.diplomacyFunctions.getResearchAgreementCost(otherCiv)
                tradeLogic.currentTrade.ourOffers.add(TradeOffer(Constants.researchAgreement, TradeType.Treaty, cost))
                tradeLogic.currentTrade.theirOffers.add(TradeOffer(Constants.researchAgreement, TradeType.Treaty, cost))

                otherCiv.tradeRequests.add(TradeRequest(civInfo.civName, tradeLogic.currentTrade.reverse()))
            }
        }


    }

    /**
     * 提出防御协定，
     */
    internal fun offerDefensivePact(civInfo: Civilization,post: Boolean=true) {
        if (!civInfo.diplomacyFunctions.canSignDefensivePact()) return // don't waste your time

        val canSignDefensivePactCiv = civInfo.getKnownCivs()
            .filter {
                civInfo.diplomacyFunctions.canSignDefensivePactWith(it)
                    && !civInfo.getDiplomacyManager(it).hasFlag(DiplomacyFlags.DeclinedDefensivePact)
            }
//             && civInfo.getDiplomacyManager(it).relationshipIgnoreAfraid() == RelationshipLevel.Ally
        for (otherCiv in canSignDefensivePactCiv) {
            // Default setting is 3, this will be changed according to different civ.
            var jsonString: String
            if (post&&DebugUtils.NEED_POST&&!DebugUtils.SIMULATEING){
//                 val content = UncivFiles.gameInfoToString(civInfo.gameInfo,false,false)
                if (DebugUtils.NEED_GameInfo) {
                    val content = UncivFiles.gameInfoToString(civInfo.gameInfo, false, false)
                    var contentData =
                        ContentData_four(content, civInfo.civName, otherCiv.civName, "form_ally")
                    jsonString = Json.encodeToString(contentData)
                }
                else {
                    val contentData =
                        ContentData_three("form_ally", civInfo.civName, otherCiv.civName)
                    jsonString = Json.encodeToString(contentData)
                }
                val postRequestResult = sendPostRequest("http://127.0.0.1:2337/get_tools", jsonString)
                val jsonObject = Json.parseToJsonElement(postRequestResult)
                val resultElement = jsonObject.jsonObject["result"]
                val resultValue: Boolean? = if (resultElement is JsonPrimitive && resultElement.contentOrNull != null) {
                    resultElement.contentOrNull!!.toBoolean() // 尝试将内容转换为布尔值
                } else {
                    null // 处理 "result" 不是布尔值或字段不存在的情况
                }
                if (resultValue == true) {
                    //todo: Add more in depth evaluation here
                    val tradeLogic = TradeLogic(civInfo, otherCiv)
                    tradeLogic.currentTrade.ourOffers.add(TradeOffer(Constants.defensivePact, TradeType.Treaty))
                    tradeLogic.currentTrade.theirOffers.add(TradeOffer(Constants.defensivePact, TradeType.Treaty))

                    otherCiv.tradeRequests.add(TradeRequest(civInfo.civName, tradeLogic.currentTrade.reverse()))
                }
            }
            else{
                if ((1..10).random() <= 3 && wantsToSignDefensivePact(civInfo, otherCiv)) {
                    //todo: Add more in depth evaluation here
                    val tradeLogic = TradeLogic(civInfo, otherCiv)
                    tradeLogic.currentTrade.ourOffers.add(TradeOffer(Constants.defensivePact, TradeType.Treaty))
                    tradeLogic.currentTrade.theirOffers.add(TradeOffer(Constants.defensivePact, TradeType.Treaty))

                    otherCiv.tradeRequests.add(TradeRequest(civInfo.civName, tradeLogic.currentTrade.reverse()))
                }
            }

        }
    }

    /**
     * 与尝试建交类似，通过一系列评估方法，确定motivation的值，大于0则返回true
     */
    fun wantsToSignDefensivePact(civInfo: Civilization, otherCiv: Civilization): Boolean {
        val diploManager = civInfo.getDiplomacyManager(otherCiv)
        if (diploManager.isRelationshipLevelLT(RelationshipLevel.Ally)) return false
        val commonknownCivs = diploManager.getCommonKnownCivs()
        // If they have bad relations with any of our friends, don't consider it
        for(thirdCiv in commonknownCivs) {
            if (civInfo.getDiplomacyManager(thirdCiv).isRelationshipLevelGE(RelationshipLevel.Friend)
                && thirdCiv.getDiplomacyManager(otherCiv).isRelationshipLevelLT(RelationshipLevel.Favorable))
                return false
        }

        val defensivePacts = civInfo.diplomacy.count { it.value.hasFlag(DiplomacyFlags.DefensivePact) }
        val otherCivNonOverlappingDefensivePacts = otherCiv.diplomacy.values.count { it.hasFlag(DiplomacyFlags.DefensivePact)
            && (!it.otherCiv().knows(civInfo) || !it.otherCiv().getDiplomacyManager(civInfo).hasFlag(DiplomacyFlags.DefensivePact)) }
        val allCivs = civInfo.gameInfo.civilizations.count { it.isMajorCiv() } - 1 // Don't include us
        val deadCivs = civInfo.gameInfo.civilizations.count { it.isMajorCiv() && !it.isAlive() }
        val allAliveCivs = allCivs - deadCivs

        // We have to already be at RelationshipLevel.Ally, so we must have 80 oppinion of them
        var motivation = diploManager.opinionOfOtherCiv().toInt() - 80

        // If they are stronger than us, then we value it a lot more
        // If they are weaker than us, then we don't value it
        motivation += when (Automation.threatAssessment(civInfo,otherCiv)) {
            ThreatLevel.VeryHigh -> 10
            ThreatLevel.High -> 5
            ThreatLevel.Low -> -15
            ThreatLevel.VeryLow -> -30
            else -> 0
        }

        // If they have a defensive pact with another civ then we would get drawn into thier battles as well
        motivation -= 10 * otherCivNonOverlappingDefensivePacts

        // Try to have a defensive pact with 1/5 of all civs
        val civsToAllyWith = 0.20f * allAliveCivs
        // Goes form 0 to -50 as the civ gets more allies, offset by civsToAllyWith
        motivation -= (50f * (defensivePacts - civsToAllyWith) / (allAliveCivs - civsToAllyWith)).coerceAtMost(0f).toInt()

        return motivation > 0
    }
    fun wantsToSignDefensivePact_civsim(civInfo: Civilization, otherCiv: Civilization):Pair<Boolean, Map<String, List<String>>>{
        var Reason_consent = mutableListOf<String>()
        var Reason_reject = mutableListOf<String>()

        val diploManager = civInfo.getDiplomacyManager(otherCiv)
        // We have to already be at RelationshipLevel.Ally, so we must have 80 oppinion of them
        var motivation = diploManager.opinionOfOtherCiv().toInt() - 80
        if (diploManager.isRelationshipLevelLT(RelationshipLevel.Ally)) {
            Reason_reject.add("We don't have a good relationship")
            motivation-=100
//             return Reason_reject
        }
        val commonknownCivs = diploManager.getCommonKnownCivs()
        // If they have bad relations with any of our friends, don't consider it
        for(thirdCiv in commonknownCivs) {
            if (civInfo.getDiplomacyManager(thirdCiv).isRelationshipLevelGE(RelationshipLevel.Friend)
                && thirdCiv.getDiplomacyManager(otherCiv).isRelationshipLevelLT(RelationshipLevel.Favorable))
            {
                Reason_reject.add("You don't get along with my friends")
                motivation-=100
//                 return Reason_reject
            }

        }

        val defensivePacts = civInfo.diplomacy.count { it.value.hasFlag(DiplomacyFlags.DefensivePact) }
        val otherCivNonOverlappingDefensivePacts = otherCiv.diplomacy.values.count { it.hasFlag(DiplomacyFlags.DefensivePact)
            && (!it.otherCiv().knows(civInfo) || !it.otherCiv().getDiplomacyManager(civInfo).hasFlag(DiplomacyFlags.DefensivePact)) }
        val allCivs = civInfo.gameInfo.civilizations.count { it.isMajorCiv() } - 1 // Don't include us
        val deadCivs = civInfo.gameInfo.civilizations.count { it.isMajorCiv() && !it.isAlive() }
        val allAliveCivs = allCivs - deadCivs

        // We have to already be at RelationshipLevel.Ally, so we must have 80 oppinion of them
//         var motivation = diploManager.opinionOfOtherCiv().toInt() - 80

        // If they are stronger than us, then we value it a lot more
        // If they are weaker than us, then we don't value it
        motivation += when (Automation.threatAssessment(civInfo,otherCiv)) {
            ThreatLevel.VeryHigh -> 10
            ThreatLevel.High -> 5
            ThreatLevel.Low -> -15
            ThreatLevel.VeryLow -> -30
            else -> 0
        }
        when (Automation.threatAssessment(civInfo,otherCiv)) {
            ThreatLevel.VeryHigh -> Reason_consent.add("You are very strong")
            ThreatLevel.High -> Reason_consent.add("You are very good")
            ThreatLevel.VeryLow -> Reason_reject.add("You're so much worse than me")
            ThreatLevel.Low -> Reason_reject.add("You're not as good as me")
            else -> 0
        }
        // If they have a defensive pact with another civ then we would get drawn into thier battles as well
        motivation -= 10 * otherCivNonOverlappingDefensivePacts
        if(otherCivNonOverlappingDefensivePacts>0)Reason_reject.add("If they have a defensive pact with another civ then we would get drawn into thier battles as well")
        // Try to have a defensive pact with 1/5 of all civs
        val civsToAllyWith = 0.20f * allAliveCivs
        // Goes form 0 to -50 as the civ gets more allies, offset by civsToAllyWith
        Reason_reject.add("I think I signed enough")
        motivation -= (50f * (defensivePacts - civsToAllyWith) / (allAliveCivs - civsToAllyWith)).coerceAtMost(0f).toInt()
        if ((50f * (defensivePacts - civsToAllyWith) / (allAliveCivs - civsToAllyWith)).coerceAtMost(0f).toInt()>0)Reason_reject.add("I think I signed enough")
        Reason_consent.add(Integer.toString(motivation))
        Reason_reject.add(Integer.toString(motivation))
        val reasonsDict: Map<String, List<String>> = mapOf("consent" to Reason_consent, "reject" to Reason_reject)
        if (motivation>0)return Pair(true,reasonsDict)
        else return Pair(false,reasonsDict)
    }

    /**
     * 通过一定的规则，ai去挑选motivation最大的城市发起战争，其中避免了ai过早发起战争。
     */
    internal fun declareWar(civInfo: Civilization,post: Boolean = true) {
//         if (civInfo.wantsToFocusOn(Victory.Focus.Culture)) return
        if (civInfo.cities.isEmpty() || civInfo.diplomacy.isEmpty()) return
//         if (civInfo.isAtWar() || civInfo.getHappiness() <= 0) return

        val ourMilitaryUnits = civInfo.units.getCivUnits().filter { !it.isCivilian() }.count()
        if (ourMilitaryUnits < civInfo.cities.size) return
        if (ourMilitaryUnits < 4) return  // to stop AI declaring war at the beginning of games when everyone isn't set up well enough\
        if (civInfo.cities.size < 3) return // FAR too early for that what are you thinking!

        //evaluate war
//         val enemyCivs = civInfo.getKnownCivs()
//             .filterNot {
//                 it == civInfo || it.cities.isEmpty() || !civInfo.getDiplomacyManager(it).canDeclareWar()
//                     || it.cities.none { city -> civInfo.hasExplored(city.getCenterTile()) }
//             }
        val enemyCivs = civInfo.getKnownCivs()
            .filterNot {
                it == civInfo || it.cities.isEmpty()
                    || it.cities.none { city -> civInfo.hasExplored(city.getCenterTile()) }
            }
        // If the AI declares war on a civ without knowing the location of any cities, it'll just keep amassing an army and not sending it anywhere,
        //   and end up at a massive disadvantage

        if (enemyCivs.none()) return

        val minMotivationToAttack = 20
        var jsonString: String
        if (post&&DebugUtils.NEED_POST&&!DebugUtils.SIMULATEING){
//             val content = UncivFiles.gameInfoToString(civInfo.gameInfo,false,false)
//             var max_name = ""
//             var max_score = 0
            for ( city in enemyCivs){
                if (DebugUtils.NEED_GameInfo) {
                    val content = UncivFiles.gameInfoToString(civInfo.gameInfo, false, false)
                    var contentData =
                        ContentData_four(content, civInfo.civName, city.civName, "declare_war")
                    jsonString = Json.encodeToString(contentData)
                }
                else {
                    var contentData =
                        ContentData_three("declare_war", civInfo.civName, city.civName)
                    jsonString = Json.encodeToString(contentData)
                }
                val postRequestResult= sendPostRequest("http://127.0.0.1:2337/get_tools",jsonString)
                val jsonObject = Json.parseToJsonElement(postRequestResult)
                val resultElement = jsonObject.jsonObject["result"]
                val resultValue: Boolean? = if (resultElement is JsonPrimitive && resultElement.contentOrNull != null) {
                    resultElement.contentOrNull!!.toBoolean() // 尝试将内容转换为布尔值
                } else {
                    null // 处理 "result" 不是布尔值或字段不存在的情况
                }
                if (resultValue == true) {
                    civInfo.getDiplomacyManager(city.civName).declareWar()
                }
//                 val score = postRequestResult.toInt()
//                 if (score>max_score) {
//                     max_score = score
//                     max_name = city.civName
//                 }
            }
//             val civWithBestMotivationToAttack = Pair(max_name,max_score)
//             if (civWithBestMotivationToAttack.second >= minMotivationToAttack)
//                 civInfo.getDiplomacyManager(civWithBestMotivationToAttack.first).declareWar()
        }
        else{
            val civWithBestMotivationToAttack = enemyCivs
                .map { Pair(it, hasAtLeastMotivationToAttack(civInfo, it, minMotivationToAttack)) }
                .maxByOrNull { it.second }!!

            if (civWithBestMotivationToAttack.second >= minMotivationToAttack)
                civInfo.getDiplomacyManager(civWithBestMotivationToAttack.first).declareWar()
        }


    }

    /** Will return the motivation to attack, but might short circuit if the value is guaranteed to
     * be lower than `atLeast`. So any values below `atLeast` should not be used for comparison. */
     fun hasAtLeastMotivationToAttack(civInfo: Civilization, otherCiv: Civilization, atLeast: Int): Int {
        val closestCities = NextTurnAutomation.getClosestCities(civInfo, otherCiv) ?: return 0
        val baseForce = 30f

        //敌我战斗力
        var ourCombatStrength = civInfo.getStatForRanking(RankingType.Force).toFloat() + baseForce
        if (civInfo.getCapital() != null) ourCombatStrength += CityCombatant(civInfo.getCapital()!!).getCityStrength()
        var theirCombatStrength = otherCiv.getStatForRanking(RankingType.Force).toFloat() + baseForce
        if(otherCiv.getCapital() != null) theirCombatStrength += CityCombatant(otherCiv.getCapital()!!).getCityStrength()

        //for city-states, also consider their protectors
        if (otherCiv.isCityState() and otherCiv.cityStateFunctions.getProtectorCivs().isNotEmpty()) {
            theirCombatStrength += otherCiv.cityStateFunctions.getProtectorCivs().filterNot { it == civInfo }
                .sumOf { it.getStatForRanking(RankingType.Force) }
        }

        if (theirCombatStrength > ourCombatStrength) return 0

        val ourCity = closestCities.city1
        val theirCity = closestCities.city2

        if (civInfo.units.getCivUnits().filter { it.isMilitary() }.none {
                val damageReceivedWhenAttacking =
                    BattleDamage.calculateDamageToAttacker(
                        MapUnitCombatant(it),
                        CityCombatant(theirCity)
                    )
                damageReceivedWhenAttacking < 100 //如果小于100，说明该单位没阵亡
            })
            return 0 // You don't have any units that can attack this city without dying, don't declare war.

        fun isTileCanMoveThrough(tile: Tile): Boolean {
            val owner = tile.getOwner()
            return !tile.isImpassible()
                && (owner == otherCiv || owner == null || civInfo.diplomacyFunctions.canPassThroughTiles(owner))
        }

        val modifierMap = HashMap<String, Int>()
        val combatStrengthRatio = ourCombatStrength / theirCombatStrength
        val combatStrengthModifier = when {
            combatStrengthRatio > 3f -> 30
            combatStrengthRatio > 2.5f -> 25
            combatStrengthRatio > 2f -> 20
            combatStrengthRatio > 1.5f -> 10
            else -> 0
        }
        modifierMap["Relative combat strength"] = combatStrengthModifier


        if (closestCities.aerialDistance > 7)
            modifierMap["Far away cities"] = -10

        val diplomacyManager = civInfo.getDiplomacyManager(otherCiv)
        if (diplomacyManager.hasFlag(DiplomacyFlags.ResearchAgreement))
            modifierMap["Research Agreement"] = -5

        if (diplomacyManager.hasFlag(DiplomacyFlags.DeclarationOfFriendship))
            modifierMap["Declaration of Friendship"] = -10

        val relationshipModifier = when (diplomacyManager.relationshipIgnoreAfraid()) {
            RelationshipLevel.Unforgivable -> 10
            RelationshipLevel.Enemy -> 5
            RelationshipLevel.Ally -> -5 // this is so that ally + DoF is not too unbalanced -
            // still possible for AI to declare war for isolated city
            else -> 0
        }
        modifierMap["Relationship"] = relationshipModifier

        if (diplomacyManager.resourcesFromTrade().any { it.amount > 0 })
            modifierMap["Receiving trade resources"] = -5

        if (theirCity.getTiles().none { tile -> tile.neighbors.any { it.getOwner() == theirCity.civ && it.getCity() != theirCity } })
            modifierMap["Isolated city"] = 15

        if (otherCiv.isCityState()) {
            modifierMap["City-state"] = -20
            if (otherCiv.getAllyCiv() == civInfo.civName)
                modifierMap["Allied City-state"] = -20 // There had better be a DAMN good reason
        }

        for (city in otherCiv.cities) {
            val construction = city.cityConstructions.getCurrentConstruction()
            if (construction is Building && construction.hasUnique(UniqueType.TriggersCulturalVictory))
                modifierMap["About to win"] = 15
            if (construction is BaseUnit && construction.hasUnique(UniqueType.AddInCapital))
                modifierMap["About to win"] = 15
        }

        var motivationSoFar = modifierMap.values.sum()

        // We don't need to execute the expensive BFSs below if we're below the threshold here
        // anyways, since it won't get better from those, only worse.
        if (motivationSoFar < atLeast) {
            return motivationSoFar
        }


        val landPathBFS = BFS(ourCity.getCenterTile()) {
            it.isLand && isTileCanMoveThrough(it)
        }

        landPathBFS.stepUntilDestination(theirCity.getCenterTile())
        if (!landPathBFS.hasReachedTile(theirCity.getCenterTile()))
            motivationSoFar -= -10

        // We don't need to execute the expensive BFSs below if we're below the threshold here
        // anyways, since it won't get better from those, only worse.
        if (motivationSoFar < atLeast) {
            return motivationSoFar
        }

        val reachableEnemyCitiesBfs = BFS(civInfo.getCapital(true)!!.getCenterTile()) { isTileCanMoveThrough(it) }
        reachableEnemyCitiesBfs.stepToEnd()
        val reachableEnemyCities = otherCiv.cities.filter { reachableEnemyCitiesBfs.hasReachedTile(it.getCenterTile()) }
        if (reachableEnemyCities.isEmpty()) return 0 // Can't even reach the enemy city, no point in war.

        return motivationSoFar
    }
    fun hasAtLeastMotivationToAttack_civsim(civInfo: Civilization, otherCiv: Civilization, atLeast: Int):Pair<Boolean, Map<String, List<String>>>  {
        val modifierMap = HashMap<String, Int>()
        var Reason_consent = mutableListOf<String>()
        var Reason_reject = mutableListOf<String>()
        val closestCities = NextTurnAutomation.getClosestCities(civInfo, otherCiv)
        if (closestCities == null) {
            Reason_reject.add("Be too far")
            Reason_consent.add(Integer.toString(-100))
            Reason_reject.add(Integer.toString(-100))
            val reasonsDict: Map<String, List<String>> = mapOf("consent" to Reason_consent, "reject" to Reason_reject)
            return Pair(false, reasonsDict)
        }
        val baseForce = 30f

        //敌我战斗力
        var ourCombatStrength = civInfo.getStatForRanking(RankingType.Force).toFloat() + baseForce
        if (civInfo.getCapital() != null) ourCombatStrength += CityCombatant(civInfo.getCapital()!!).getCityStrength()
        var theirCombatStrength = otherCiv.getStatForRanking(RankingType.Force).toFloat() + baseForce
        if(otherCiv.getCapital() != null) theirCombatStrength += CityCombatant(otherCiv.getCapital()!!).getCityStrength()

        //for city-states, also consider their protectors
        if (otherCiv.isCityState() and otherCiv.cityStateFunctions.getProtectorCivs().isNotEmpty()) {
            theirCombatStrength += otherCiv.cityStateFunctions.getProtectorCivs().filterNot { it == civInfo }
                .sumOf { it.getStatForRanking(RankingType.Force) }
        }

        if (theirCombatStrength > ourCombatStrength) {
            Reason_reject.add("It's so much better than us")
            modifierMap["Relative combat strength less"] = -50
//             return Reason_reject
        }

        val ourCity = closestCities.city1
        val theirCity = closestCities.city2

        if (civInfo.units.getCivUnits().filter { it.isMilitary() }.none {
                val damageReceivedWhenAttacking =
                    BattleDamage.calculateDamageToAttacker(
                        MapUnitCombatant(it),
                        CityCombatant(theirCity)
                    )
                damageReceivedWhenAttacking < 100 //如果小于100，说明该单位没阵亡
            })
        {
            Reason_reject.add("Our sacrifice will be heavy")
//             return Reason_reject
        }
             // You don't have any units that can attack this city without dying, don't declare war.

        fun isTileCanMoveThrough(tile: Tile): Boolean {
            val owner = tile.getOwner()
            return !tile.isImpassible()
                && (owner == otherCiv || owner == null || civInfo.diplomacyFunctions.canPassThroughTiles(owner))
        }

        val combatStrengthRatio = ourCombatStrength / theirCombatStrength
        val combatStrengthModifier = when {
            combatStrengthRatio > 3f -> 30
            combatStrengthRatio > 2.5f -> 25
            combatStrengthRatio > 2f -> 20
            combatStrengthRatio > 1.5f -> 10
            else -> 0
        }
        modifierMap["Relative combat strength"] = combatStrengthModifier
        when {
            combatStrengthRatio > 3f -> Reason_consent.add("Our military force completely overwhelmed the opposite side")
            combatStrengthRatio > 2.5f -> Reason_consent.add("We have far more military power than the other side")
            combatStrengthRatio > 2f -> Reason_consent.add("We have more military power than the other side")
            combatStrengthRatio > 1.5f -> Reason_consent.add("Our military strength is slightly stronger than the opposite side")
            else -> 0
        }

        if (closestCities.aerialDistance > 7){
            modifierMap["Far away cities"] = -10
            Reason_reject.add("Far away cities")
        }


        val diplomacyManager = civInfo.getDiplomacyManager(otherCiv)
        if (diplomacyManager.hasFlag(DiplomacyFlags.ResearchAgreement)){
            modifierMap["Research Agreement"] = -5
            Reason_reject.add("We signed a research agreement")
        }


        if (diplomacyManager.hasFlag(DiplomacyFlags.DeclarationOfFriendship)){
            modifierMap["Declaration of Friendship"] = -10
            Reason_reject.add("We declared our friendship")
        }


        val relationshipModifier = when (diplomacyManager.relationshipIgnoreAfraid()) {
            RelationshipLevel.Unforgivable -> 10
            RelationshipLevel.Enemy -> 5
            RelationshipLevel.Ally -> -5 // this is so that ally + DoF is not too unbalanced -
            // still possible for AI to declare war for isolated city
            else -> 0
        }
        when (diplomacyManager.relationshipIgnoreAfraid()) {
            RelationshipLevel.Unforgivable -> Reason_consent.add("Our relationship is unforgivable")
            RelationshipLevel.Enemy -> Reason_consent.add("We have a hostile relationship")
            RelationshipLevel.Ally -> Reason_reject.add("We are Allies")// this is so that ally + DoF is not too unbalanced -
            // still possible for AI to declare war for isolated city
            else -> 0
        }
        modifierMap["Relationship"] = relationshipModifier

        if (diplomacyManager.resourcesFromTrade().any { it.amount > 0 }){
            modifierMap["Receiving trade resources"] = -5
            Reason_reject.add("We do business")
        }


        if (theirCity.getTiles().none { tile -> tile.neighbors.any { it.getOwner() == theirCity.civ && it.getCity() != theirCity } }){
            modifierMap["Isolated city"] = 15
            Reason_consent.add("They were alone")
        }


        if (otherCiv.isCityState()) {
            modifierMap["City-state"] = -20
            Reason_reject.add("They're just city-states")
            if (otherCiv.getAllyCiv() == civInfo.civName){
                modifierMap["Allied City-state"] = -20 // There had better be a DAMN good reason
                Reason_reject.add("It's a confederate city")
            }

        }

        for (city in otherCiv.cities) {
            val construction = city.cityConstructions.getCurrentConstruction()
            if (construction is Building && construction.hasUnique(UniqueType.TriggersCulturalVictory)){
                modifierMap["About to win"] = 15
                Reason_consent.add("We can't let the other side win")
            }

            if (construction is BaseUnit && construction.hasUnique(UniqueType.AddInCapital)){
                Reason_consent.add("We can't let the other side win")
                modifierMap["About to win"] = 15
            }
        }

        var motivationSoFar = modifierMap.values.sum()

        // We don't need to execute the expensive BFSs below if we're below the threshold here
        // anyways, since it won't get better from those, only worse.
        if (motivationSoFar < atLeast) {
            Reason_consent.add(Integer.toString(motivationSoFar))
            Reason_reject.add(Integer.toString(motivationSoFar))
            val reasonsDict: Map<String, List<String>> = mapOf("consent" to Reason_consent, "reject" to Reason_reject)
            return Pair(false,reasonsDict)
        }


        val landPathBFS = BFS(ourCity.getCenterTile()) {
            it.isLand && isTileCanMoveThrough(it)
        }

        landPathBFS.stepUntilDestination(theirCity.getCenterTile())
        if (!landPathBFS.hasReachedTile(theirCity.getCenterTile())){
            motivationSoFar -= -10
            Reason_reject.add("There's no way to get there")
        }


        // We don't need to execute the expensive BFSs below if we're below the threshold here
        // anyways, since it won't get better from those, only worse.
        if (motivationSoFar < atLeast) {
            Reason_consent.add(Integer.toString(motivationSoFar))
            Reason_reject.add(Integer.toString(motivationSoFar))
            val reasonsDict: Map<String, List<String>> = mapOf("consent" to Reason_consent, "reject" to Reason_reject)
            return Pair(false,reasonsDict)
        }

        val reachableEnemyCitiesBfs = BFS(civInfo.getCapital(true)!!.getCenterTile()) { isTileCanMoveThrough(it) }
        reachableEnemyCitiesBfs.stepToEnd()
        val reachableEnemyCities = otherCiv.cities.filter { reachableEnemyCitiesBfs.hasReachedTile(it.getCenterTile()) }
        Reason_consent.add(Integer.toString(motivationSoFar))
        Reason_reject.add(Integer.toString(motivationSoFar))
        val reasonsDict: Map<String, List<String>> = mapOf("consent" to Reason_consent, "reject" to Reason_reject)
        if (reachableEnemyCities.isEmpty()) Pair(false,reasonsDict) // Can't even reach the enemy city, no point in war.

        return Pair(true,reasonsDict)
    }
    /**
     * 提出和平协议，通过一定规则去判断，当motivation依旧大于10时会有拒绝的情况。。。
     */
    internal fun offerPeaceTreaty(civInfo: Civilization,post: Boolean = true) {
        if (!civInfo.isAtWar() || civInfo.cities.isEmpty() || civInfo.diplomacy.isEmpty()) return

        val enemiesCiv = civInfo.diplomacy.filter { it.value.diplomaticStatus == DiplomaticStatus.War }
            .map { it.value.otherCiv() }
            .filterNot { it == civInfo || it.isBarbarian() || it.cities.isEmpty() }
            .filter { !civInfo.getDiplomacyManager(it).hasFlag(DiplomacyFlags.DeclinedPeace) }
            // Don't allow AIs to offer peace to city states allied with their enemies
            .filterNot { it.isCityState() && it.getAllyCiv() != null && civInfo.isAtWarWith(civInfo.gameInfo.getCivilization(it.getAllyCiv()!!)) }
            // ignore civs that we have already offered peace this turn as a counteroffer to another civ's peace offer
            .filter { it.tradeRequests.none { tradeRequest -> tradeRequest.requestingCiv == civInfo.civName && tradeRequest.trade.isPeaceTreaty() } }

        var jsonString: String
        for (enemy in enemiesCiv) {
            if (post&&DebugUtils.NEED_POST&&!DebugUtils.SIMULATEING){
//                 val content = UncivFiles.gameInfoToString(civInfo.gameInfo,false,false)
                if (DebugUtils.NEED_GameInfo) {
                    val content = UncivFiles.gameInfoToString(civInfo.gameInfo, false, false)
                    val contentData = ContentData_four(content, civInfo.civName, enemy.civName, "speek_peace")
                    jsonString = Json.encodeToString(contentData)
                }
                else {
                    val contentData =
                        ContentData_three("speek_peace", civInfo.civName, enemy.civName)
                    jsonString = Json.encodeToString(contentData)
                }
                val postRequestResult= sendPostRequest("http://127.0.0.1:2337/get_tools",jsonString)
                val jsonObject = Json.parseToJsonElement(postRequestResult)
                val resultElement = jsonObject.jsonObject["result"]
                val resultValue: Boolean? = if (resultElement is JsonPrimitive && resultElement.contentOrNull != null) {
                    resultElement.contentOrNull!!.toBoolean() // 尝试将内容转换为布尔值
                } else {
                    null // 处理 "result" 不是布尔值或字段不存在的情况
                }
//                 val score = postRequestResult.toInt()
//             if(hasAtLeastMotivationToAttack(civInfo, enemy, 10) >= 10) {
                if (resultValue == false){
                    // We can still fight. Refuse peace.
                    continue
                }
            }
           else{
                if(hasAtLeastMotivationToAttack(civInfo, enemy, 10) >= 10) {
                    // We can still fight. Refuse peace.
                    continue
                }
           }
            // pay for peace
            val tradeLogic = TradeLogic(civInfo, enemy)

            tradeLogic.currentTrade.ourOffers.add(TradeOffer(Constants.peaceTreaty, TradeType.Treaty))
            tradeLogic.currentTrade.theirOffers.add(TradeOffer(Constants.peaceTreaty, TradeType.Treaty))

            var moneyWeNeedToPay = -TradeEvaluation().evaluatePeaceCostForThem(civInfo, enemy)//赔钱评估

            if (civInfo.gold > 0 && moneyWeNeedToPay > 0) {
                if (moneyWeNeedToPay > civInfo.gold) {
                    moneyWeNeedToPay = civInfo.gold  // As much as possible
                }
                tradeLogic.currentTrade.ourOffers.add(
                    TradeOffer("Gold".tr(), TradeType.Gold, moneyWeNeedToPay)
                )
            }

            enemy.tradeRequests.add(TradeRequest(civInfo.civName, tradeLogic.currentTrade.reverse()))
        }
    }

}
// 发送 POST 请求
fun sendPostRequest(url: String, postData: String): String {
    val url = URL(url)
    val connection = url.openConnection() as HttpURLConnection
    connection.requestMethod = "POST"
    connection.setRequestProperty("Content-Type", "application/json")
    connection.doOutput = true

    val wr = OutputStreamWriter(connection.outputStream)
    wr.write(postData)
    wr.flush()

    val bufferedReader = BufferedReader(InputStreamReader(connection.inputStream))
    val response = StringBuffer()

    var inputLine: String?
    while (bufferedReader.readLine().also { inputLine = it } != null) {
        response.append(inputLine)
    }
    bufferedReader.close()

    return response.toString()
}

@Serializable
data class ContentData_four(val gameinfo: String, val civ1: String, val civ2: String, val skill:String)
@Serializable
data class ContentData_three(val gameinfo: String, val civ1: String, val civ2: String)
@Serializable
data class ContentData_two(val gameinfo: String, val civ1: String)
@Serializable
data class ContentData_unit(val gameinfo: String, val civ1: String,val id:String)

fun sendcanSignResearchAgreementsWith(content:String,civInfo1: Civilization,civInfo2: Civilization): Boolean? {
        var jsonString: String
        if (DebugUtils.NEED_GameInfo) {
            val gameinfo = UncivFiles.gameInfoToString(civInfo1.gameInfo,false,false)
            val contentData = ContentData_four(gameinfo, civInfo1.civName, civInfo2.civName,content)
            jsonString = Json.encodeToString(contentData)
        }
        else {
            val contentData = ContentData_three(content, civInfo1.civName, civInfo2.civName)
            jsonString = Json.encodeToString(contentData)
        }
//         val jsonString = Json.encodeToString(contentData)
        val postRequestResult= sendPostRequest("http://127.0.0.1:2337/get_tools",jsonString)
        val jsonObject = Json.parseToJsonElement(postRequestResult)
        val resultElement = jsonObject.jsonObject["result"]
        val resultValue: Boolean? = if (resultElement is JsonPrimitive && resultElement.contentOrNull != null) {
            resultElement.contentOrNull!!.toBoolean() // 尝试将内容转换为布尔值
        } else {
            null // 处理 "result" 不是布尔值或字段不存在的情况
        }
        return resultValue
}

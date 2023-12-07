package com.unciv

import com.unciv.logic.GameInfo
import com.unciv.logic.files.UncivFiles
import com.unciv.models.metadata.GameSettings
import java.io.File

open class Gametext(){

    fun main(file_path:String,PreTurns:Int){
        val file = File(file_path)
        val content: String = file.readText()
        val uncivGame = UncivGame()
        UncivGame.Current = uncivGame
        val gameSettings = GameSettings()
        uncivGame.settings = gameSettings
        if (content != null) {
            val game: GameInfo = UncivFiles.gameInfoFromString_easy(content)
            uncivGame.gameInfo = game
            UncivGame.Current = uncivGame
            game.nextTenTurn(PreTurns)
        }

    }

}
